import {
  Component, OnInit, OnDestroy, inject,
  signal, computed, ChangeDetectionStrategy
} from '@angular/core';
import { CommonModule, DecimalPipe, PercentPipe, SlicePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { interval, Subscription, switchMap, startWith, catchError, of } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { MachinePrediction, RiskTier, LogFixRequest } from '../../core/models';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, FormsModule, RouterLink, DecimalPipe, PercentPipe, SlicePipe],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css'
})
export class DashboardComponent implements OnInit, OnDestroy {
  private api = inject(ApiService);

  predictions   = signal<Record<string, MachinePrediction>>({});
  cIndex        = signal<number | null>(null);
  modelVersion  = signal('');
  loading       = signal(true);
  retraining    = signal(false);
  mlHealthy     = signal(false);
  panel         = signal<MachinePrediction | null>(null);
  submitting    = signal(false);
  submitOk      = signal(false);
  submitErr     = signal('');

  searchTerm = '';
  selCat     = '';
  selTier    = signal('ALL');

  fixForm: LogFixRequest = {
    machineCode: '', eventDate: today(),
    interventionType: 'C', durationH: 1,
    notes: '', technicianId: ''
  };

  readonly tiers: RiskTier[] = ['CRITICAL','HIGH','MEDIUM','LOW'];
  private sub?: Subscription;

  list     = computed(() => Object.values(this.predictions()).filter(m => m != null && m.machineCode != null));
  total    = computed(() => this.list().length);
  critical = computed(() => this.list().filter(m => m.riskTier === 'CRITICAL').length);
  high     = computed(() => this.list().filter(m => m.riskTier === 'HIGH').length);
  down     = computed(() => this.list().filter(m => m.isCurrentlyDown).length);
  cats     = computed(() => [...new Set(this.list().map(m => m.category).filter(Boolean))].sort());
  ciStr    = computed(() => this.cIndex() != null ? this.cIndex()!.toFixed(3) : '—');

  filtered = computed(() => {
    const ord: Record<string,number> = {CRITICAL:0,HIGH:1,MEDIUM:2,LOW:3,UNKNOWN:4};
    return this.list()
      .filter(m => {
        if (this.selTier() !== 'ALL' && m.riskTier !== this.selTier()) return false;
        if (this.selCat && m.category !== this.selCat) return false;
        if (this.searchTerm) {
          const q = this.searchTerm.toLowerCase();
          return m.machineCode.toLowerCase().includes(q) ||
                 (m.machineName?.toLowerCase() ?? '').includes(q);
        }
        return true;
      })
      .sort((a,b) => (ord[a.riskTier]??4)-(ord[b.riskTier]??4));
  });

  countTier(t: string) { return this.list().filter(m => m.riskTier === t).length; }
  hazardPct(m: MachinePrediction) { return Math.round(m.hazardScore * 100); }

  ngOnInit() {
    this.sub = interval(600_000).pipe(
      startWith(0),
      switchMap(() => this.api.getAllDualPredictions().pipe(catchError(() => of(null))))
    ).subscribe(r => {
      if (r) {
        this.predictions.set(r.predictions ?? {});
        this.cIndex.set(r.modelSummary?.cIndex ?? null);
        this.modelVersion.set(r.modelVersion ?? '');
      }
      this.loading.set(false);
    });
    this.api.getMlHealth().pipe(catchError(() => of({status:'down'} as any)))
      .subscribe(h => this.mlHealthy.set(h?.status === 'ok'));
  }

  ngOnDestroy() { this.sub?.unsubscribe(); }

  setTier(t: string) { this.selTier.set(t); }

  openPanel(m: MachinePrediction) {
    this.panel.set(m);
    this.fixForm = { ...this.fixForm, machineCode: m.machineCode, eventDate: today() };
    this.submitOk.set(false); this.submitErr.set('');
  }
  closePanel() { this.panel.set(null); }

  retrain() {
    this.retraining.set(true);
    this.api.triggerRetrain().pipe(catchError(() => of(null))).subscribe(() => {
      this.retraining.set(false);
      this.api.getAllDualPredictions().pipe(catchError(() => of(null))).subscribe(r => {
        if (r) { this.predictions.set(r.predictions ?? {}); this.cIndex.set(r.modelSummary?.cIndex ?? null); }
      });
    });
  }

  logFix() {
    const machineCode = this.panel()?.machineCode;
    if (!machineCode) { this.submitErr.set('No machine selected.'); return; }
    this.fixForm.machineCode = machineCode;
    console.log('[SICAME] Logging fix:', this.fixForm);
    this.submitting.set(true); this.submitOk.set(false); this.submitErr.set('');
    this.api.logFix(this.fixForm).pipe(catchError(e => of({error: e.message}))).subscribe(r => {
      this.submitting.set(false);
      if (r?.error) { this.submitErr.set(r.error); return; }
      this.submitOk.set(true);
      const refresh = () => {
        this.api.getAllDualPredictions().pipe(catchError(() => of(null))).subscribe(resp => {
          if (resp?.predictions) {
            this.predictions.set(resp.predictions);
            if (resp.predictions[machineCode]) this.panel.set(resp.predictions[machineCode]);
          }
        });
      };
      setTimeout(refresh, 6000);
      setTimeout(refresh, 12000);
    });
  }

  track(_: number, m: MachinePrediction) { return m.machineCode; }
}

function today() { return new Date().toISOString().slice(0,10); }
