"""
=============================================================================
SICAME - Machine Breakdown Prediction Engine  (Dual Pipeline)
=============================================================================
Pipeline 1 — Cox PH Survival Model (lifelines)
Pipeline 2 — Classical ML (sklearn + XGBoost)
=============================================================================
"""

import os, json, logging, warnings, hashlib, joblib
import numpy as np
import pandas as pd
from datetime import datetime, timedelta, date
from pathlib import Path
from typing import Optional

from lifelines import CoxPHFitter, KaplanMeierFitter
from lifelines.utils import concordance_index

from sklearn.ensemble        import (RandomForestClassifier, GradientBoostingClassifier,
                                     RandomForestRegressor, GradientBoostingRegressor)
from sklearn.linear_model    import LinearRegression, Ridge, Lasso, LogisticRegression
from sklearn.preprocessing   import StandardScaler, RobustScaler
from sklearn.model_selection import cross_val_score, GridSearchCV
from sklearn.metrics         import (mean_absolute_error, mean_squared_error,
                                     r2_score, roc_auc_score)
try:
    from xgboost import XGBRegressor, XGBClassifier
    XGBOOST_OK = True
except ImportError:
    XGBOOST_OK = False

warnings.filterwarnings("ignore")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("sicame_ml.log", encoding="utf-8"),
    ],
)
logger = logging.getLogger("SICAME-ML")

INTERVENTION_SEVERITY = {
    "C":2.0,"IC":1.8,"CP":1.5,"Q":1.2,
    "PP":0.6,"P":0.4,"G":0.3,"INS":0.1,
}
RISK_THRESHOLDS       = {"CRITICAL":0.75,"HIGH":0.50,"MEDIUM":0.25}
MIN_EVENTS_FOR_COX    = 3
PRODUCTION_LOSS_FACTOR= 1.5
MODEL_DIR             = Path("sicame_models")
DATA_DIR              = Path("sicame_data")


# =============================================================================
# DATA LAYER
# =============================================================================

class DataManager:
    def __init__(self, mh_csv_path="mh2.csv", eq_csv_path="Equipement.csv",
                 data_dir: Path = DATA_DIR):
        self.mh_path        = Path(mh_csv_path)
        self.eq_path        = Path(eq_csv_path)
        self.data_dir       = data_dir
        self.data_dir.mkdir(exist_ok=True)
        self.augmented_path = self.data_dir / "augmented_events.csv"
        self._sanitise_augmented()
        logger.info("DataManager initialised")

    def _sanitise_augmented(self):
        if not self.augmented_path.exists():
            return
        try:
            df = pd.read_csv(self.augmented_path, dtype={"machine_code": str})
            df["machine_code"] = (df["machine_code"].astype(str).str.strip()
                                   .str.replace(r"\.0$","",regex=True))
            df = df[df["machine_code"].str.len() > 0]
            df = df[~df["machine_code"].isin(["nan","none","null",""])]
            df.to_csv(self.augmented_path, index=False)
            logger.info(f"augmented_events.csv sanitised: {len(df)} rows")
        except Exception as e:
            logger.warning(f"Could not sanitise augmented CSV: {e}")

    def load_equipment(self) -> pd.DataFrame:
        eq = pd.read_csv(self.eq_path)
        eq = eq.rename(columns={
            "CODEREF":"machine_code","Equipement":"name",
            "NB":"quantity","Consommation en KWh":"power_kw",
            "ListeDesEquipements":"category_raw",
        })
        eq["category"]     = eq["category_raw"].ffill()
        eq["machine_code"] = eq["machine_code"].astype(str).str.strip()
        eq["power_kw"]     = pd.to_numeric(eq["power_kw"],errors="coerce").fillna(5.0)
        return eq[["machine_code","name","category","quantity","power_kw"]].dropna(subset=["machine_code"])

    def load_maintenance_history(self) -> pd.DataFrame:
        df = pd.read_csv(self.mh_path, header=None,
            names=["date","machine_code","intervention_type","duration_h"],
            na_values=["\\N","NULL","\\\\N"])
        df["date"]         = pd.to_datetime(df["date"],errors="coerce")
        df["machine_code"] = df["machine_code"].astype(str).str.strip()
        df["duration_h"]   = pd.to_numeric(df["duration_h"],errors="coerce")
        df["source"]       = "historical"
        return df.dropna(subset=["date","machine_code"])

    def load_augmented_events(self) -> pd.DataFrame:
        if not self.augmented_path.exists():
            return pd.DataFrame(columns=["date","machine_code","intervention_type","duration_h","source"])
        df = pd.read_csv(self.augmented_path, parse_dates=["date"], dtype={"machine_code":str})
        df["source"]       = "dashboard"
        df["machine_code"] = (df["machine_code"].astype(str).str.strip()
                               .str.replace(r"\.0$","",regex=True))
        before = len(df)
        df = df[df["machine_code"].str.len() > 0]
        df = df[~df["machine_code"].isin(["nan","none","null",""])]
        if len(df) < before:
            df.to_csv(self.augmented_path, index=False)
        return df

    def append_event(self, machine_code, event_date, intervention_type, duration_h, notes="") -> dict:
        machine_code = str(machine_code).strip()
        if not machine_code:
            return {"status":"error","reason":"machine_code is required"}
        new_row = pd.DataFrame([{
            "date":              pd.to_datetime(event_date),
            "machine_code":      machine_code,
            "intervention_type": intervention_type.upper(),
            "duration_h":        float(duration_h),
            "notes":             notes,
            "logged_at":         datetime.utcnow().isoformat(),
            "source":            "dashboard",
        }])
        if self.augmented_path.exists():
            existing = pd.read_csv(self.augmented_path, dtype={"machine_code":str})
            updated  = pd.concat([existing, new_row], ignore_index=True)
        else:
            updated = new_row
        updated["machine_code"] = (updated["machine_code"].astype(str).str.strip()
                                    .str.replace(r"\.0$","",regex=True))
        updated.to_csv(self.augmented_path, index=False)
        logger.info(f"New event appended: machine='{machine_code}', type={intervention_type}, date={event_date}, duration={duration_h}h")
        (self.data_dir / ".retrain_needed").touch()
        return {"status":"ok","rows_total":len(updated)}

    def load_all(self) -> pd.DataFrame:
        hist = self.load_maintenance_history()
        aug  = self.load_augmented_events()
        df   = pd.concat([hist, aug], ignore_index=True)
        df["date"] = pd.to_datetime(df["date"], errors="coerce")
        df = df.dropna(subset=["date"])
        df["machine_code"] = (df["machine_code"].astype(str).str.strip()
                               .str.replace(r"\.0$","",regex=True))
        df = df[df["machine_code"].str.len() > 0]
        df = df[~df["machine_code"].isin(["nan","none","null",""])]
        df = df.sort_values(["machine_code","date"]).reset_index(drop=True)
        logger.info(f"Total records loaded: {len(df)} ({len(hist)} historical + {len(aug)} dashboard)")
        return df


# =============================================================================
# PIPELINE 1 FEATURE ENGINEERING
# =============================================================================

class FeatureEngineer:
    def __init__(self, observation_end: Optional[date] = None):
        self.observation_end = observation_end or date.today()

    def _severity(self, itype):
        return INTERVENTION_SEVERITY.get(str(itype).strip().upper(), 1.0)

    def _weighted_sev(self, window_df):
        if window_df.empty: return 0.0
        total = sum(
            row["severity"] if row["is_breakdown"] else -row["severity"]*0.5
            for _, row in window_df.iterrows()
        )
        return round(max(0.0, total), 4)

    def build_survival_dataset(self, df) -> pd.DataFrame:
        records = []
        obs_end = pd.Timestamp(self.observation_end)
        for machine, grp in df.groupby("machine_code"):
            grp = grp.sort_values("date").reset_index(drop=True)
            grp["is_breakdown"] = grp["intervention_type"].isin(["C","IC","CP"]).astype(int)
            grp["severity"]     = grp["intervention_type"].map(self._severity)
            grp["duration_h"]   = grp["duration_h"].fillna(
                grp["duration_h"].median() if grp["duration_h"].notna().any() else 1.0)
            grp["cum_C"] = grp["is_breakdown"].cumsum()
            grp["cum_P"] = grp["intervention_type"].isin(["P","PP","G"]).cumsum()
            breakdowns   = grp[grp["is_breakdown"]==1].index.tolist()
            start_date   = grp["date"].iloc[0]
            prev_idx     = None
            for ep in breakdowns + ["END"]:
                end_date      = obs_end if ep=="END" else grp.loc[ep,"date"]
                event_val     = 0 if ep=="END" else 1
                duration_days = max((end_date - start_date).days, 1)
                ref    = obs_end if ep=="END" else end_date
                last_7  = grp[(grp["date"] >= ref-pd.Timedelta(days=7))  & (grp["date"] < ref)]
                last_30 = grp[(grp["date"] >= ref-pd.Timedelta(days=30)) & (grp["date"] < ref)]
                w7  = self._weighted_sev(last_7)
                w30 = self._weighted_sev(last_30)
                prev_p = grp[(grp["date"]<start_date) & grp["intervention_type"].isin(["P","PP","G"])]
                days_since_p = (start_date-prev_p["date"].max()).days if not prev_p.empty else 365
                prev_events = grp[grp["date"]<start_date].tail(3)
                mean_sev_3  = prev_events["severity"].mean() if not prev_events.empty else 1.0
                mb  = grp["date"]<start_date
                mp  = grp["intervention_type"].isin(["P","PP","G"])
                cum_C_start = int(grp.loc[mb,"is_breakdown"].sum())
                cum_P_start = int(grp.loc[mb & mp,"machine_code"].count())
                p_ratio     = cum_P_start / (cum_C_start + 1)
                records.append({
                    "machine_code":               machine,
                    "duration":                   duration_days,
                    "event":                      event_val,
                    "last_severity":              grp.loc[prev_idx,"severity"] if prev_idx is not None else 0.0,
                    "mean_severity_3":            mean_sev_3,
                    "cum_breakdowns":             cum_C_start,
                    "p_ratio":                    round(p_ratio,4),
                    "days_since_last_preventive": min(days_since_p,365),
                    "rolling_7d_events":          w7,
                    "rolling_30d_events":         w30,
                    "bd_rate_30d":                round(last_30["is_breakdown"].sum()/30.0,6),
                    "interval_start":             start_date,
                    "interval_end":               end_date,
                })
                if ep != "END":
                    start_date = end_date
                    prev_idx   = ep
        survival_df = pd.DataFrame(records)
        survival_df = survival_df[survival_df["duration"]>0].reset_index(drop=True)
        logger.info(f"Survival dataset built: {len(survival_df)} intervals, "
                    f"{survival_df['event'].sum()} observed events, "
                    f"{(survival_df['event']==0).sum()} censored")
        return survival_df

    def build_current_features(self, df) -> pd.DataFrame:
        obs_end = pd.Timestamp(self.observation_end)
        rows = []
        for machine, grp in df.groupby("machine_code"):
            grp = grp.sort_values("date").reset_index(drop=True)
            grp["is_breakdown"] = grp["intervention_type"].isin(["C","IC","CP"]).astype(int)
            grp["severity"]     = grp["intervention_type"].map(self._severity)
            last_event     = grp.iloc[-1]
            last_breakdown = grp[grp["is_breakdown"]==1]
            last_prev      = grp[grp["intervention_type"].isin(["P","PP","G"])]
            days_since_bd = (obs_end-last_breakdown["date"].max()).days if not last_breakdown.empty else 999
            days_since_p  = (obs_end-last_prev["date"].max()).days     if not last_prev.empty      else 365
            last_7  = grp[grp["date"] >= obs_end-pd.Timedelta(days=7)]
            last_30 = grp[grp["date"] >= obs_end-pd.Timedelta(days=30)]
            last_3  = grp.tail(3)
            total_days = max((obs_end-grp["date"].min()).days, 1)
            bd7  = int(last_7["is_breakdown"].sum())
            bd30 = int(last_30["is_breakdown"].sum())
            rows.append({
                "machine_code":               machine,
                # ── Cox features — exact names in COX_FEATURES ──────────────
                "last_severity":              last_event["severity"],
                "mean_severity_3":            round(last_3["severity"].mean(),4),
                "cum_breakdowns":             int(grp["is_breakdown"].sum()),
                "p_ratio":                    round(grp["intervention_type"].isin(["P","PP","G"]).sum()/(grp["is_breakdown"].sum()+1),4),
                "days_since_last_preventive": min(days_since_p,365),
                "rolling_7d_events":          self._weighted_sev(last_7),
                "rolling_30d_events":         self._weighted_sev(last_30),
                "bd_rate_30d":                round(bd30/30.0,6),
                # ── Display / classifier features ───────────────────────────
                "days_since_last_breakdown":  min(days_since_bd,999),
                "cum_preventive":             int(grp["intervention_type"].isin(["P","PP","G"]).sum()),
                "rolling_7d_breakdowns":      bd7,
                "rolling_30d_breakdowns":     bd30,
                "bd_rate_total":              round(grp["is_breakdown"].sum()/total_days,6),
                "total_downtime_h":           round(grp.loc[grp["is_breakdown"]==1,"duration_h"].sum(),2),
                "last_intervention_type":     last_event["intervention_type"],
                "observation_days":           total_days,
            })
        return pd.DataFrame(rows)


# =============================================================================
# PIPELINE 1 — COX PH + RISK CLASSIFIER
# =============================================================================

class SurvivalModel:
    COX_FEATURES = [
        "last_severity","mean_severity_3","cum_breakdowns","p_ratio",
        "days_since_last_preventive","rolling_7d_events","rolling_30d_events","bd_rate_30d",
    ]

    def __init__(self):
        self.cox       = CoxPHFitter(penalizer=0.1)
        self.km_global = KaplanMeierFitter()
        self.fitted    = False
        self.c_index   = None
        self.n_events  = 0

    def fit(self, survival_df) -> "SurvivalModel":
        self.n_events = int(survival_df["event"].sum())
        self.km_global.fit(survival_df["duration"], event_observed=survival_df["event"], label="global_km")
        if self.n_events < MIN_EVENTS_FOR_COX:
            self.fitted = True
            return self
        cox_cols = ["duration","event"] + self.COX_FEATURES
        cox_data = survival_df[cox_cols].dropna()
        for col in self.COX_FEATURES:
            cox_data[col] = cox_data[col].clip(upper=cox_data[col].quantile(0.99))
        try:
            self.cox.fit(cox_data, duration_col="duration", event_col="event", show_progress=False)
            self.fitted = True
            try:
                self.c_index = concordance_index(
                    cox_data["duration"], -self.cox.predict_partial_hazard(cox_data), cox_data["event"])
                logger.info(f"Cox model fitted. C-index: {self.c_index:.4f}, Events: {self.n_events}")
            except Exception:
                self.c_index = 0.5
        except Exception as e:
            logger.error(f"Cox fitting failed: {e}")
            self.fitted = True
        return self

    def predict_survival(self, feature_row, horizons=[7,14,30]) -> dict:
        result = {}
        cox_vec = pd.DataFrame([{col: feature_row.get(col,0.0) for col in self.COX_FEATURES}])
        if self.fitted and self.cox.params_ is not None:
            try:
                sf = self.cox.predict_survival_function(cox_vec)
                for h in horizons:
                    idx = sf.index; nearest = idx[np.argmin(np.abs(idx-h))]
                    result[f"survival_{h}d"] = round(max(0.0,min(1.0,float(sf.loc[nearest].iloc[0]))),4)
                result["raw_partial_hazard"] = float(self.cox.predict_partial_hazard(cox_vec).iloc[0])
            except Exception:
                self._km_predict(result, horizons)
        else:
            self._km_predict(result, horizons)
        if "raw_partial_hazard" in result:
            ph = result.pop("raw_partial_hazard")
            result["hazard_score"] = round(1/(1+np.exp(-np.log(ph+1e-9))),4)
        else:
            result["hazard_score"] = round(1.0-result.get("survival_7d",0.5),4)
        s7=result.get("survival_7d",0.5); s14=result.get("survival_14d",0.4); s30=result.get("survival_30d",0.3)
        result["reliability_index"] = round((s7+s14+s30)/3.0,4)
        try:
            median_surv = float(self.km_global.median_survival_time_)
            result["days_until_predicted_next"] = max(0, round(median_surv - float(feature_row.get("days_since_last_breakdown",0))))
        except Exception:
            result["days_until_predicted_next"] = -1
        return result

    def _km_predict(self, result, horizons):
        tl = self.km_global.survival_function_.index
        sv = self.km_global.survival_function_["global_km"].values
        for h in horizons:
            result[f"survival_{h}d"] = round(float(sv[np.argmin(np.abs(tl-h))]),4)

    def summary(self) -> dict:
        out = {"model_type":"CoxPH" if (self.fitted and self.cox.params_ is not None) else "KaplanMeier",
               "n_events":self.n_events,"c_index":self.c_index,"rf_cv_f1":None}
        if self.fitted and self.cox.params_ is not None:
            try:
                out["coefficients"] = self.cox.summary[["coef","exp(coef)","p"]].round(4).to_dict()
            except Exception: pass
        return out


class RiskClassifier:
    FEATURES = [
        "days_since_last_breakdown","days_since_last_preventive","cum_breakdowns","p_ratio",
        "mean_severity_3","rolling_7d_events","rolling_7d_breakdowns","rolling_30d_events",
        "rolling_30d_breakdowns","bd_rate_30d","bd_rate_total","last_severity","observation_days",
    ]
    LABEL_MAP = {0:"LOW",1:"MEDIUM",2:"HIGH",3:"CRITICAL"}

    def __init__(self):
        self.rf           = RandomForestClassifier(n_estimators=200,max_depth=8,min_samples_leaf=2,class_weight="balanced",random_state=42,n_jobs=-1)
        self.gb           = GradientBoostingClassifier(n_estimators=100,max_depth=4,learning_rate=0.05,random_state=42)
        self.scaler       = StandardScaler()
        self.fitted       = False
        self.cv_score     = None
        self.feature_cols = []

    def _label(self, h):
        if h>=RISK_THRESHOLDS["CRITICAL"]: return 3
        if h>=RISK_THRESHOLDS["HIGH"]:     return 2
        if h>=RISK_THRESHOLDS["MEDIUM"]:   return 1
        return 0

    def fit(self, current_features, survival_predictions) -> "RiskClassifier":
        df = current_features.copy()
        df["hazard_score"] = df["machine_code"].map({k:v.get("hazard_score",0.5) for k,v in survival_predictions.items()}).fillna(0.5)
        df["label"] = df["hazard_score"].apply(self._label)
        self.feature_cols = [c for c in self.FEATURES if c in df.columns]
        X = df[self.feature_cols].fillna(0); y = df["label"]
        if len(y.unique()) < 2: return self
        Xs = self.scaler.fit_transform(X)
        self.rf.fit(Xs,y); self.gb.fit(Xs,y)
        try:
            self.cv_score = round(float(cross_val_score(self.rf,Xs,y,cv=min(5,len(y)),scoring="f1_weighted").mean()),4)
            logger.info(f"Risk classifier CV F1 (weighted): {self.cv_score}")
        except Exception: pass
        self.fitted = True
        return self

    def predict(self, feature_row) -> dict:
        if not self.fitted: return {"risk_tier":"UNKNOWN","risk_probabilities":{}}
        X = pd.DataFrame([{c:feature_row.get(c,0) for c in self.feature_cols}]).fillna(0)
        Xs = self.scaler.transform(X)
        prf=self.rf.predict_proba(Xs)[0]; pgb=self.gb.predict_proba(Xs)[0]
        avg=np.zeros(4)
        for i,c in enumerate(self.rf.classes_): avg[c]+=(prf[i]+pgb[i])/2
        tier=self.LABEL_MAP.get(int(np.argmax(avg)),"UNKNOWN")
        return {"risk_tier":tier,"risk_probabilities":{
            "LOW":round(float(avg[0]),4),"MEDIUM":round(float(avg[1]),4),
            "HIGH":round(float(avg[2]),4),"CRITICAL":round(float(avg[3]),4)}}

    def feature_importance(self) -> list:
        if not self.fitted: return []
        return sorted([{"feature":f,"importance":round(float(i),5)}
                       for f,i in zip(self.feature_cols,self.rf.feature_importances_)],
                      key=lambda x:x["importance"],reverse=True)


# =============================================================================
# PIPELINE 2 — CLASSICAL ML
# =============================================================================

class ClassicalMLPipeline:
    FEATURE_COLS = [
        "days_since_last_C","days_since_last_P",
        "cur_14d","cur_30d","cur_60d","cur_90d",
        "prev_14d","prev_30d","prev_60d","prev_90d",
        "hours_14d","hours_30d","hours_60d","hours_90d",
        "curative_rate_per_month","preventive_rate_per_month",
        "total_curatives","trend_C_30d","prev_to_cur_ratio_90d",
    ]

    def __init__(self):
        self.scaler           = RobustScaler()
        self.best_reg         = None
        self.best_clf         = None
        self.fitted           = False
        self.avg_curative_h   = 1.4
        self.avg_preventive_h = 1.0
        self.all_reg_results  = []
        self.all_clf_results  = []

    def _build_features(self, df) -> pd.DataFrame:
        df_cp = df[df["intervention_type"].isin(["C","P"])].copy()
        df_cp = df_cp.rename(columns={"date":"intervention_date","duration_h":"execution_time_hours"})
        med_C = df_cp.loc[df_cp["intervention_type"]=="C","execution_time_hours"].median() or 1.4
        med_P = df_cp.loc[df_cp["intervention_type"]=="P","execution_time_hours"].median() or 1.0
        df_cp.loc[(df_cp["intervention_type"]=="C")&df_cp["execution_time_hours"].isna(),"execution_time_hours"] = med_C
        df_cp.loc[(df_cp["intervention_type"]=="P")&df_cp["execution_time_hours"].isna(),"execution_time_hours"] = med_P
        self.avg_curative_h   = float(df_cp.loc[df_cp["intervention_type"]=="C","execution_time_hours"].mean() or 1.4)
        self.avg_preventive_h = float(df_cp.loc[df_cp["intervention_type"]=="P","execution_time_hours"].mean() or 1.0)
        cc = df_cp[df_cp["intervention_type"]=="C"].groupby("machine_code").size()
        valid = cc[cc>=2].index
        df_cp = df_cp[df_cp["machine_code"].isin(valid)].sort_values(["machine_code","intervention_date"]).reset_index(drop=True)
        rows = []
        for machine, grp in df_cp.groupby("machine_code"):
            events    = grp.sort_values("intervention_date").reset_index(drop=True)
            curatives = events[events["intervention_type"]=="C"].reset_index(drop=True)
            if len(curatives) < 2: continue
            for i in range(len(curatives)-1):
                cur_date  = curatives.loc[i,"intervention_date"]
                next_date = curatives.loc[i+1,"intervention_date"]
                days_until= (next_date-cur_date).days
                if days_until < 0: continue
                past   = events[events["intervention_date"]<=cur_date]
                past_C = past[past["intervention_type"]=="C"]
                past_P = past[past["intervention_type"]=="P"]
                prev_C = past_C[past_C["intervention_date"]<cur_date]
                dsc = (cur_date-prev_C["intervention_date"].max()).days if not prev_C.empty else 999
                dsp = (cur_date-past_P["intervention_date"].max()).days if not past_P.empty else 999
                def win(days):
                    cut=cur_date-timedelta(days=days); w=past[past["intervention_date"]>=cut]
                    return int((w["intervention_type"]=="C").sum()),int((w["intervention_type"]=="P").sum()),float(w["execution_time_hours"].sum())
                c14,p14,h14=win(14); c30,p30,h30=win(30); c60,p60,h60=win(60); c90,p90,h90=win(90)
                obs_days  = max((cur_date-events["intervention_date"].min()).days,1)
                cur_rate  = len(past_C)/obs_days*30
                prev_rate = len(past_P)/obs_days*30
                c30_prev  = win(60)[0]-c30; trend_C=c30-c30_prev
                rows.append({
                    "machine":machine,
                    "days_since_last_C":dsc,"days_since_last_P":dsp,
                    "cur_14d":c14,"cur_30d":c30,"cur_60d":c60,"cur_90d":c90,
                    "prev_14d":p14,"prev_30d":p30,"prev_60d":p60,"prev_90d":p90,
                    "hours_14d":h14,"hours_30d":h30,"hours_60d":h60,"hours_90d":h90,
                    "curative_rate_per_month":round(cur_rate,4),
                    "preventive_rate_per_month":round(prev_rate,4),
                    "total_curatives":len(past_C),
                    "trend_C_30d":trend_C,
                    "prev_to_cur_ratio_90d":round(p90/max(c90,1),4),
                    "target_days":min(days_until,200),
                    "target_30d":int(days_until<=30),
                    "target_14d":int(days_until<=14),
                })
        return pd.DataFrame(rows)

    def fit(self, df) -> "ClassicalMLPipeline":
        logger.info("Classical ML Pipeline — training started")
        feat_df = self._build_features(df)
        if len(feat_df) < 10:
            logger.warning("Not enough samples for Classical ML — skipping")
            return self
        X=feat_df[self.FEATURE_COLS].fillna(0); yr=feat_df["target_days"]; y30=feat_df["target_30d"]
        Xs = self.scaler.fit_transform(X); SEED=42
        # Regression
        reg_candidates = [
            ("Linear Regression",   LinearRegression()),
            ("Ridge (α=best)",      GridSearchCV(Ridge(),{"alpha":[0.1,1,10,100]},cv=5,scoring="neg_mean_absolute_error",n_jobs=-1)),
            ("Lasso (α=best)",      GridSearchCV(Lasso(max_iter=5000),{"alpha":[0.01,0.1,1,10]},cv=5,scoring="neg_mean_absolute_error",n_jobs=-1)),
            ("Random Forest",       RandomForestRegressor(n_estimators=300,max_depth=8,min_samples_leaf=2,max_features="sqrt",random_state=SEED,n_jobs=-1)),
            ("Gradient Boosting",   GradientBoostingRegressor(n_estimators=200,learning_rate=0.08,max_depth=4,subsample=0.8,random_state=SEED)),
        ]
        if XGBOOST_OK: reg_candidates.append(("XGBoost",XGBRegressor(n_estimators=200,learning_rate=0.08,max_depth=4,subsample=0.8,colsample_bytree=0.8,random_state=SEED,verbosity=0)))
        self.all_reg_results = []
        for name, model in reg_candidates:
            try:
                model.fit(Xs,yr); pred=np.clip(model.predict(Xs),0,None)
                mae=mean_absolute_error(yr,pred); rmse=float(np.sqrt(mean_squared_error(yr,pred))); r2=r2_score(yr,pred)
                self.all_reg_results.append({"name":name,"model":model,"mae":round(mae,1),"rmse":round(rmse,1),"r2":round(r2,3)})
                logger.info(f"  Reg [{name}] MAE={mae:.1f}d R²={r2:.3f}")
            except Exception as e: logger.warning(f"  Reg [{name}] failed: {e}")
        if self.all_reg_results:
            self.best_reg = min(self.all_reg_results, key=lambda x:x["mae"])
            logger.info(f"Best regressor: {self.best_reg['name']} MAE={self.best_reg['mae']}")
        # Classification
        if y30.sum()>=2 and (len(y30)-y30.sum())>=2:
            clf_candidates=[
                ("Logistic Regression",LogisticRegression(max_iter=500,random_state=SEED,class_weight="balanced")),
                ("Random Forest",      RandomForestClassifier(n_estimators=200,max_depth=6,class_weight="balanced",random_state=SEED,n_jobs=-1)),
                ("Gradient Boosting",  GradientBoostingClassifier(n_estimators=100,learning_rate=0.08,max_depth=4,random_state=SEED)),
            ]
            if XGBOOST_OK: clf_candidates.append(("XGBoost",XGBClassifier(n_estimators=100,learning_rate=0.08,max_depth=4,random_state=SEED,verbosity=0,eval_metric="logloss")))
            self.all_clf_results = []
            for name, model in clf_candidates:
                try:
                    model.fit(Xs,y30); proba=model.predict_proba(Xs)[:,1] if hasattr(model,"predict_proba") else model.decision_function(Xs)
                    auc=roc_auc_score(y30,proba); acc=float((model.predict(Xs)==y30).mean())
                    self.all_clf_results.append({"name":name,"model":model,"auc":round(auc,3),"acc":round(acc,3)})
                    logger.info(f"  Clf [{name}] AUC={auc:.3f}")
                except Exception as e: logger.warning(f"  Clf [{name}] failed: {e}")
            if self.all_clf_results:
                self.best_clf = max(self.all_clf_results, key=lambda x:x["auc"])
                logger.info(f"Best classifier: {self.best_clf['name']} AUC={self.best_clf['auc']}")
        self.fitted = True
        logger.info("Classical ML Pipeline — training complete")
        return self

    def predict_machine(self, machine_code, df) -> dict:
        if not self.fitted or self.best_reg is None: return {"error":"not trained"}
        hist = df[df["machine_code"]==machine_code].copy()
        hist = hist[hist["intervention_type"].isin(["C","P"])]
        hist = hist.rename(columns={"date":"intervention_date","duration_h":"execution_time_hours"})
        if hist.empty or (hist["intervention_type"]=="C").sum()<1: return {"error":f"insufficient data for {machine_code}"}
        today=hist["intervention_date"].max(); past_C=hist[hist["intervention_type"]=="C"]; past_P=hist[hist["intervention_type"]=="P"]
        def win(days):
            cut=today-timedelta(days=days); w=hist[hist["intervention_date"]>=cut]
            return int((w["intervention_type"]=="C").sum()),int((w["intervention_type"]=="P").sum()),float(w["execution_time_hours"].fillna(0).sum())
        c14,p14,h14=win(14); c30,p30,h30=win(30); c60,p60,h60=win(60); c90,p90,h90=win(90)
        prev_C=past_C[past_C["intervention_date"]<today]
        dsc=(today-prev_C["intervention_date"].max()).days if not prev_C.empty else 999
        dsp=(today-past_P["intervention_date"].max()).days if not past_P.empty else 999
        obs_days=max((today-hist["intervention_date"].min()).days,1)
        cur_rate=len(past_C)/obs_days*30; prev_rate=len(past_P)/obs_days*30
        c30_prev=win(60)[0]-c30; trend_C=c30-c30_prev
        features=pd.DataFrame([[dsc,dsp,c14,c30,c60,c90,p14,p30,p60,p90,h14,h30,h60,h90,cur_rate,prev_rate,len(past_C),trend_C,p90/max(c90,1)]],columns=self.FEATURE_COLS)
        Xs=self.scaler.transform(features.fillna(0))
        days_pred=float(max(0,self.best_reg["model"].predict(Xs)[0]))
        risk_30d=0.5; risk_14d=0.3
        if self.best_clf and hasattr(self.best_clf["model"],"predict_proba"):
            risk_30d=float(self.best_clf["model"].predict_proba(Xs)[0,1])
            risk_14d=min(1.0,risk_30d*0.7)
        time_saved=round(self.avg_curative_h*PRODUCTION_LOSS_FACTOR-self.avg_preventive_h,2)
        return {
            "days_to_failure":      round(days_pred,1),
            "breakdown_in_30d":     round(risk_30d*100,1),
            "breakdown_in_14d":     round(risk_14d*100,1),
            "best_regressor_name":  self.best_reg["name"],
            "best_regressor_mae":   self.best_reg["mae"],
            "best_classifier_name": self.best_clf["name"] if self.best_clf else "N/A",
            "best_classifier_auc":  self.best_clf["auc"]  if self.best_clf else 0.0,
            "time_saved_h":         time_saved,
            "avg_curative_h":       round(self.avg_curative_h,2),
            "avg_preventive_h":     round(self.avg_preventive_h,2),
        }

    def summary(self) -> dict:
        return {
            "fitted":              self.fitted,
            "best_regressor":      self.best_reg["name"] if self.best_reg else None,
            "best_regressor_mae":  self.best_reg["mae"]  if self.best_reg else None,
            "best_classifier":     self.best_clf["name"] if self.best_clf else None,
            "best_classifier_auc": self.best_clf["auc"]  if self.best_clf else None,
            "avg_curative_h":      round(self.avg_curative_h,2),
            "avg_preventive_h":    round(self.avg_preventive_h,2),
        }


# =============================================================================
# PREDICTION ENGINE
# =============================================================================

class BreakdownPredictionEngine:
    def __init__(self, data_manager: DataManager, model_dir: Path = MODEL_DIR):
        self.dm               = data_manager
        self.model_dir        = model_dir
        self.model_dir.mkdir(exist_ok=True)
        self.survival_model   = SurvivalModel()
        self.risk_classifier  = RiskClassifier()
        self.classical_ml     = ClassicalMLPipeline()
        self.feature_eng      = FeatureEngineer()
        self._survival_predictions: dict     = {}
        self._current_features: pd.DataFrame = pd.DataFrame()
        self._equipment_df:    pd.DataFrame  = pd.DataFrame()
        self._raw_df:          pd.DataFrame  = pd.DataFrame()
        self._last_trained:    Optional[str] = None
        self._model_version:   str           = ""

    def train(self, force=False) -> dict:
        logger.info("=== Training pipeline started ===")
        t0     = datetime.utcnow()
        raw_df = self.dm.load_all()
        eq_df  = self.dm.load_equipment()
        self._equipment_df = eq_df
        self._raw_df       = raw_df
        fe = FeatureEngineer(observation_end=date.today())
        self.feature_eng = fe
        survival_df = fe.build_survival_dataset(raw_df)
        current_df  = fe.build_current_features(raw_df)
        self._current_features = current_df
        self.survival_model = SurvivalModel()
        self.survival_model.fit(survival_df)
        surv_preds = {row["machine_code"]: self.survival_model.predict_survival(row)
                      for _, row in current_df.iterrows()}
        self._survival_predictions = surv_preds
        self.risk_classifier = RiskClassifier()
        self.risk_classifier.fit(current_df, surv_preds)
        sm = self.survival_model.summary()
        sm["rf_cv_f1"] = self.risk_classifier.cv_score
        self.classical_ml = ClassicalMLPipeline()
        self.classical_ml.fit(raw_df)
        self._last_trained  = datetime.utcnow().isoformat()
        self._model_version = self._compute_version(raw_df)
        self._save_models()
        flag = self.dm.data_dir / ".retrain_needed"
        if flag.exists(): flag.unlink()
        elapsed = (datetime.utcnow()-t0).total_seconds()
        summary = {
            "status":"trained","trained_at":self._last_trained,
            "model_version":self._model_version,"elapsed_s":round(elapsed,2),
            "n_records":len(raw_df),"n_machines":len(current_df),
            "n_events":self.survival_model.n_events,
            "cox_c_index":self.survival_model.c_index,
            "rf_cv_f1":self.risk_classifier.cv_score,
            "survival_model":sm,"classical_ml":self.classical_ml.summary(),
        }
        logger.info(f"Training complete in {elapsed:.2f}s — {summary}")
        return summary

    def predict_all(self) -> dict:
        if not self.survival_model.fitted: self.train()
        eq_lookup = self._equipment_df.set_index("machine_code").to_dict("index") if not self._equipment_df.empty else {}
        results = {}
        for _, row in self._current_features.iterrows():
            m=row["machine_code"]; surv=self.survival_model.predict_survival(row)
            risk=self.risk_classifier.predict(row); eq_info=eq_lookup.get(m,{})
            results[m]={
                "machine_code":m,
                "machine_name":     eq_info.get("name",f"Machine {m}"),
                "category":         eq_info.get("category","Unknown"),
                "power_kw":         eq_info.get("power_kw",5.0),
                "survival_7d":      surv.get("survival_7d",0.5),
                "survival_14d":     surv.get("survival_14d",0.4),
                "survival_30d":     surv.get("survival_30d",0.3),
                "hazard_score":     surv.get("hazard_score",0.5),
                "reliability_index":surv.get("reliability_index",0.5),
                "days_until_predicted_next":surv.get("days_until_predicted_next",-1),
                "risk_tier":        risk.get("risk_tier","UNKNOWN"),
                "risk_probabilities":risk.get("risk_probabilities",{}),
                "days_since_last_breakdown": int(row.get("days_since_last_breakdown",0)),
                "days_since_last_preventive":int(row.get("days_since_last_preventive",0)),
                "cum_breakdowns":   int(row.get("cum_breakdowns",0)),
                "cum_preventive":   int(row.get("cum_preventive",0)),
                "total_downtime_h": float(row.get("total_downtime_h",0.0)),
                "model_confidence": round(min(1.0,int(row.get("cum_breakdowns",0))/10.0),4),
                "predicted_at":     datetime.utcnow().isoformat(),
                "model_version":    self._model_version,
                "is_currently_down":False,
                "current_status":   "OPERATIONAL",
            }
        logger.info(f"Predictions generated for {len(results)} machines.")
        return {"predictions":results,"model_summary":self.survival_model.summary(),
                "generated_at":datetime.utcnow().isoformat(),"model_version":self._model_version}

    def predict_one(self, machine_code) -> dict:
        return self.predict_all()["predictions"].get(machine_code, {"error":"machine not found"})

    def predict_all_dual(self) -> dict:
        p1 = self.predict_all()
        dual = {}
        for m, cox_pred in p1["predictions"].items():
            p2 = {}
            if self.classical_ml.fitted and not self._raw_df.empty:
                try:    p2 = self.classical_ml.predict_machine(m, self._raw_df)
                except Exception as e: p2 = {"error":str(e)}
            dual[m] = {**cox_pred, "pipeline2": p2}
        return {"predictions":dual,"model_summary":self.survival_model.summary(),
                "classical_ml":self.classical_ml.summary(),
                "generated_at":datetime.utcnow().isoformat(),"model_version":self._model_version}

    def predict_one_dual(self, machine_code) -> dict:
        return self.predict_all_dual()["predictions"].get(machine_code, {"error":"machine not found"})

    def log_fix_and_retrain(self, machine_code, event_date, intervention_type, duration_h, notes="") -> dict:
        append_result = self.dm.append_event(machine_code,event_date,intervention_type,duration_h,notes)
        logger.info("Fix logged — retraining now.")
        train_result = self.train(force=True)
        prediction   = self.predict_one_dual(machine_code)
        return {"append_status":append_result,"train_status":train_result,"new_prediction":prediction}

    def get_feature_importance(self) -> list:
        return self.risk_classifier.feature_importance()

    def _compute_version(self, df) -> str:
        try:
            max_date = pd.to_datetime(df["date"],errors="coerce").max()
            ms = str(max_date.date()) if pd.notna(max_date) else "unknown"
        except Exception: ms="unknown"
        return f"{date.today().isoformat()}-{hashlib.md5((str(len(df))+ms).encode()).hexdigest()[:8]}"

    def _save_models(self):
        joblib.dump(self.survival_model,    self.model_dir/"survival_model.pkl")
        joblib.dump(self.risk_classifier,   self.model_dir/"risk_classifier.pkl")
        joblib.dump(self.classical_ml,      self.model_dir/"classical_ml.pkl")
        joblib.dump(self._current_features, self.model_dir/"current_features.pkl")
        joblib.dump(self._raw_df,           self.model_dir/"raw_df.pkl")
        with open(self.model_dir/"meta.json","w") as f:
            json.dump({"last_trained":self._last_trained,"model_version":self._model_version,
                       "n_events":self.survival_model.n_events,"c_index":self.survival_model.c_index},f,indent=2,default=str)
        logger.info(f"Models saved to {self.model_dir}")

    def load_models(self) -> bool:
        try:
            self.survival_model    = joblib.load(self.model_dir/"survival_model.pkl")
            self.risk_classifier   = joblib.load(self.model_dir/"risk_classifier.pkl")
            self.classical_ml      = joblib.load(self.model_dir/"classical_ml.pkl")
            self._current_features = joblib.load(self.model_dir/"current_features.pkl")
            self._raw_df           = joblib.load(self.model_dir/"raw_df.pkl")
            with open(self.model_dir/"meta.json") as f: meta=json.load(f)
            self._last_trained=meta["last_trained"]; self._model_version=meta["model_version"]
            logger.info(f"Models loaded — version {self._model_version}")
            return True
        except Exception as e:
            logger.warning(f"Could not load saved models: {e}")
            return False


# =============================================================================
# SCHEDULER
# =============================================================================

class DailyRetrainScheduler:
    def __init__(self, engine, hour=2):
        import threading
        self.engine=engine; self.hour=hour
        self._stop=threading.Event()
        self._thread=threading.Thread(target=self._run,daemon=True,name="SICAME-Retrain")

    def start(self):
        self._thread.start()
        logger.info(f"Scheduler started — fires at {self.hour:02d}:00")

    def stop(self): self._stop.set()

    def _run(self):
        import time as _time
        while not self._stop.is_set():
            now=datetime.now(); flag=self.engine.dm.data_dir/".retrain_needed"
            if now.hour==self.hour and now.minute<5:
                logger.info("Scheduled daily retraining."); self.engine.train(); _time.sleep(310)
            elif flag.exists():
                logger.info("Retrain flag detected — retraining now."); self.engine.train()
            else: _time.sleep(60)


# =============================================================================
# REST ADAPTER
# =============================================================================

class MLRestAdapter:
    def __init__(self, engine, port=8765):
        self.engine=engine; self.port=port

    def start(self):
        from http.server import BaseHTTPRequestHandler, HTTPServer
        engine=self.engine

        class Handler(BaseHTTPRequestHandler):
            def log_message(self,fmt,*args): logger.debug(fmt%args)

            def _send_json(self,payload,status=200):
                body=json.dumps(payload,default=str).encode()
                self.send_response(status)
                self.send_header("Content-Type","application/json")
                self.send_header("Content-Length",str(len(body)))
                self.send_header("Access-Control-Allow-Origin","*")
                self.end_headers(); self.wfile.write(body)

            def do_GET(self):
                path=self.path.split("?")[0]
                if   path=="/ml/health":         self._send_json({"status":"ok","model_version":engine._model_version,"last_trained":engine._last_trained})
                elif path=="/ml/predict":         self._send_json(engine.predict_all())
                elif path=="/ml/predict-dual":    self._send_json(engine.predict_all_dual())
                elif path.startswith("/ml/predict-dual/"): self._send_json(engine.predict_one_dual(path.split("/ml/predict-dual/")[1]))
                elif path.startswith("/ml/predict/"):      self._send_json(engine.predict_one(path.split("/ml/predict/")[1]))
                elif path=="/ml/feature-importance": self._send_json({"features":engine.get_feature_importance()})
                elif path=="/ml/train":           self._send_json(engine.train())
                else:                             self._send_json({"error":"not found"},404)

            def do_POST(self):
                try:
                    cl=self.headers.get("Content-Length"); te=self.headers.get("Transfer-Encoding","")
                    if cl is not None: raw=self.rfile.read(int(cl))
                    elif "chunked" in te.lower():
                        raw=b""
                        while True:
                            sl=self.rfile.readline().strip()
                            if not sl: break
                            cs=int(sl,16)
                            if cs==0: break
                            raw+=self.rfile.read(cs); self.rfile.readline()
                    else: raw=self.rfile.read()
                    bs=raw.decode("utf-8",errors="replace").strip()
                    if bs.startswith("\ufeff"): bs=bs[1:]
                    import re as _re
                    if bs and _re.match(r"^[0-9a-fA-F]+\r?\n",bs):
                        js=bs.find("{"); je=bs.rfind("}")+1
                        if js>=0 and je>js: bs=bs[js:je]
                    try: payload=json.loads(bs) if bs else {}
                    except json.JSONDecodeError:
                        js=bs.find("{"); je=bs.rfind("}")+1
                        payload=json.loads(bs[js:je]) if js>=0 else {}
                    logger.info(f"do_POST {self.path} | CL={cl} TE={te!r} | raw_bytes={len(raw)} | keys={list(payload.keys())}")
                    if   self.path=="/ml/train":
                        self._send_json(engine.train(force=True))
                    elif self.path=="/ml/log-fix":
                        self._send_json(engine.log_fix_and_retrain(
                            machine_code=payload.get("machine_code",""),
                            event_date=payload.get("event_date",date.today().isoformat()),
                            intervention_type=payload.get("intervention_type","C"),
                            duration_h=float(payload.get("duration_h",1.0)),
                            notes=payload.get("notes",""),
                        ))
                    elif self.path=="/ml/append-event":
                        self._send_json(engine.dm.append_event(
                            machine_code=payload.get("machine_code",""),
                            event_date=payload.get("event_date",""),
                            intervention_type=payload.get("intervention_type","P"),
                            duration_h=float(payload.get("duration_h",1.0)),
                            notes=payload.get("notes",""),
                        ))
                    else: self._send_json({"error":"not found"},404)
                except Exception as exc:
                    import traceback
                    logger.error(f"do_POST error on {self.path}: {exc}\n{traceback.format_exc()}")
                    try: self._send_json({"error":str(exc),"status":"error"},500)
                    except Exception: pass

            def do_OPTIONS(self):
                self.send_response(200)
                self.send_header("Access-Control-Allow-Origin","*")
                self.send_header("Access-Control-Allow-Methods","GET,POST,OPTIONS")
                self.send_header("Access-Control-Allow-Headers","Content-Type")
                self.end_headers()

        server=HTTPServer(("0.0.0.0",self.port),Handler)
        logger.info(f"ML REST server listening on port {self.port}")
        server.serve_forever()


# =============================================================================
# MAIN
# =============================================================================

def main():
    mh_csv    = os.getenv("SICAME_MH_CSV",        "mh2.csv")
    eq_csv    = os.getenv("SICAME_EQ_CSV",         "Equipement.csv")
    model_dir = Path(os.getenv("SICAME_MODEL_DIR", "sicame_models"))
    data_dir  = Path(os.getenv("SICAME_DATA_DIR",  "sicame_data"))
    port      = int(os.getenv("SICAME_PORT",       "8765"))
    retrain_h = int(os.getenv("SICAME_RETRAIN_HOUR","2"))
    logger.info("="*60); logger.info("SICAME Machine Breakdown Prediction Engine (Dual Pipeline)"); logger.info("="*60)
    dm=DataManager(mh_csv,eq_csv,data_dir); engine=BreakdownPredictionEngine(dm,model_dir)
    if not engine.load_models():
        logger.info("No saved models — training from scratch."); engine.train()
    else:
        flag=data_dir/".retrain_needed"
        if flag.exists(): logger.info("Retrain flag found — retraining."); engine.train()
    DailyRetrainScheduler(engine,hour=retrain_h).start()
    MLRestAdapter(engine,port=port).start()

if __name__=="__main__":
    main()
