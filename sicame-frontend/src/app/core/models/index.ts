export type RiskTier = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN';

export interface MachinePrediction {
  machineCode:             string;
  machineName:             string;
  category:                string;
  powerKw:                 number;
  currentStatus:           'OPERATIONAL' | 'BROKEN_DOWN' | 'MAINTENANCE';
  isCurrentlyDown:         boolean;
  survival7d:              number;
  survival14d:             number;
  survival30d:             number;
  riskTier:                RiskTier;
  riskProbabilities:       Record<string, number>;
  hazardScore:             number;
  reliabilityIndex:        number;
  daysSinceLastBreakdown:  number;
  daysSinceLastPreventive: number;
  daysUntilPredictedNext:  number;
  cumBreakdowns:           number;
  cumPreventive:           number;
  totalDowntimeH:          number;
  modelConfidence:         number;
  predictedAt:             string;
  modelVersion:            string;
}

export interface ModelSummary {
  modelType: string;
  nEvents:   number;
  cIndex:    number | null;
  rfCvF1:    number | null;
}

export interface AllPredictionsResponse {
  predictions:  Record<string, MachinePrediction>;
  modelSummary: ModelSummary;
  generatedAt:  string;
  modelVersion: string;
}

export interface DailyProductivity {
  date:              string;
  idealHours:        number;
  actualHours:       number;
  lostHours:         number;
  productivityPct:   number;
  energyConsumedKwh: number;
  energyWastedKwh:   number;
  hadBreakdown:      boolean;
  breakdownType:     string | null;
}

export interface MachineProductivity {
  machineCode:      string;
  machineName:      string;
  category:         string;
  powerKw:          number;
  dailySeries:      DailyProductivity[];
  idealHoursTotal:  number;
  actualHoursTotal: number;
  lostHoursTotal:   number;
  productivityPct:  number;
  energyIdealKwh:   number;
  energyConsumedKwh:number;
  energyWastedKwh:  number;
  carbonImpactKg:   number;
  period:           string;
}

export interface GlobalProductivity {
  dailySeries:            DailyProductivity[];
  productivityByMachine:  Record<string, number>;
  productivityByCategory: Record<string, number>;
  overallProductivityPct: number;
  totalIdealHours:        number;
  totalActualHours:       number;
  totalLostHours:         number;
  totalEnergyIdealKwh:    number;
  totalEnergyConsumedKwh: number;
  totalEnergyWastedKwh:   number;
  carbonImpactKg:         number;
  period:                 string;
}

export interface Equipment {
  machineCode:      string;
  name:             string;
  category:         string;
  quantity:         number;
  powerKw:          number;
  idealHoursPerDay: number;
}

export interface LogFixRequest {
  machineCode:      string;
  eventDate:        string;
  interventionType: string;
  durationH:        number;
  notes:            string;
  technicianId:     string;
}

export interface MlHealth {
  status:       string;
  modelVersion: string;
  lastTrained:  string;
}

export interface FeatureImportanceItem {
  feature:    string;
  importance: number;
}
