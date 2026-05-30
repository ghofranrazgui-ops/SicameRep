import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  AllPredictionsResponse, MachinePrediction,
  MachineProductivity, GlobalProductivity,
  Equipment, LogFixRequest, MlHealth, FeatureImportanceItem
} from '../models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api';

  /* ── ML Pipeline 1 (Cox) ─────────────────────────────── */
  getAllPredictions(): Observable<AllPredictionsResponse> {
    return this.http.get<AllPredictionsResponse>(`${this.base}/ml/predictions`);
  }
  getMachinePrediction(code: string): Observable<MachinePrediction> {
    return this.http.get<MachinePrediction>(`${this.base}/ml/predictions/${code}`);
  }

  /* ── ML Dual Pipeline ────────────────────────────────── */
  getAllDualPredictions(): Observable<AllPredictionsResponse> {
    return this.http.get<AllPredictionsResponse>(`${this.base}/ml/dual-predictions`);
  }
  getDualPrediction(code: string): Observable<MachinePrediction> {
    return this.http.get<MachinePrediction>(`${this.base}/ml/dual-predictions/${code}`);
  }

  /* ── ML Control ──────────────────────────────────────── */
  triggerRetrain(): Observable<any> {
    return this.http.post<any>(`${this.base}/ml/train`, {});
  }
  getMlHealth(): Observable<MlHealth> {
    return this.http.get<MlHealth>(`${this.base}/ml/health`);
  }
  getFeatureImportance(): Observable<{ features: FeatureImportanceItem[] }> {
    return this.http.get<{ features: FeatureImportanceItem[] }>(`${this.base}/ml/feature-importance`);
  }

  /* ── Events ──────────────────────────────────────────── */
  logFix(req: LogFixRequest): Observable<any> {
    return this.http.post<any>(`${this.base}/events/log-fix`, req);
  }

  /* ── Equipment ───────────────────────────────────────── */
  getEquipment(): Observable<Equipment[]> {
    return this.http.get<Equipment[]>(`${this.base}/equipment`);
  }

  /* ── Productivity ────────────────────────────────────── */
  getGlobalProductivity(from: string, to: string): Observable<GlobalProductivity> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<GlobalProductivity>(`${this.base}/productivity/global`, { params });
  }
  getMachineProductivity(code: string, from: string, to: string): Observable<MachineProductivity> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<MachineProductivity>(`${this.base}/productivity/${code}`, { params });
  }
}
