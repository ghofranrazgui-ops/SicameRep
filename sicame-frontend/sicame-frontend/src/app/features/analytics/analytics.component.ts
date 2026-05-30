import {
  Component, OnInit, AfterViewInit, OnDestroy,
  inject, signal, computed, ViewChild, ElementRef,
  ChangeDetectionStrategy, ChangeDetectorRef
} from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import {
  GlobalProductivity, MachineProductivity,
  DailyProductivity, Equipment, FeatureImportanceItem
} from '../../core/models';
import { Chart, registerables } from 'chart.js';
Chart.register(...registerables);

interface Period { key:string; label:string; from:string; to:string; }

@Component({
  selector: 'app-analytics',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, FormsModule, RouterLink, DecimalPipe],
  templateUrl: './analytics.component.html',
  styleUrl: './analytics.component.css'
})
export class AnalyticsComponent implements OnInit, AfterViewInit, OnDestroy {
  private api = inject(ApiService);
  private cdr = inject(ChangeDetectorRef);

  /* ── canvas refs ── */
  @ViewChild('chartProd')   chartProdRef!:  ElementRef<HTMLCanvasElement>;
  @ViewChild('chartPct')    chartPctRef!:   ElementRef<HTMLCanvasElement>;
  @ViewChild('chartEnergy') chartEnergyRef!:ElementRef<HTMLCanvasElement>;
  @ViewChild('chartCumul')  chartCumulRef!: ElementRef<HTMLCanvasElement>;
  @ViewChild('chartBar')    chartBarRef!:   ElementRef<HTMLCanvasElement>;
  @ViewChild('chartDonut')  chartDonutRef!: ElementRef<HTMLCanvasElement>;
  @ViewChild('chartFeat')   chartFeatRef!:  ElementRef<HTMLCanvasElement>;

  /* ── state ── */
  loading       = signal(true);
  globalData    = signal<GlobalProductivity|null>(null);
  machineData   = signal<MachineProductivity|null>(null);
  equipment     = signal<Equipment[]>([]);
  features      = signal<FeatureImportanceItem[]>([]);
  selMachine    = signal<string|null>(null);
  selPeriod     = signal('full');
  customFrom    = '2005-01-01';
  customTo      = '2005-12-31';

  private charts: Chart[] = [];
  private viewReady = false;

  /* ── periods ── */
  readonly periods: Period[] = [
    {key:'full', label:'Full Year', from:'2005-01-01', to:'2005-12-31'},
    {key:'q1',   label:'Q1',        from:'2005-01-01', to:'2005-03-31'},
    {key:'q2',   label:'Q2',        from:'2005-04-01', to:'2005-06-30'},
    {key:'q3',   label:'Q3',        from:'2005-07-01', to:'2005-09-30'},
    {key:'q4',   label:'Q4',        from:'2005-10-01', to:'2005-12-31'},
    {key:'custom',label:'Custom',    from:'',           to:''},
  ];

  /* ── derived ── */
  series = computed<DailyProductivity[]>(() =>
    this.selMachine() ? (this.machineData()?.dailySeries ?? [])
                      : (this.globalData()?.dailySeries ?? []));

  effPct = computed(() => {
    const g = this.globalData();
    if (!g || !g.totalEnergyIdealKwh) return 100;
    return (g.totalEnergyConsumedKwh / g.totalEnergyIdealKwh) * 100;
  });

  /* ── lifecycle ── */
  ngOnInit() {
    this.api.getEquipment().pipe(catchError(()=>of([]))).subscribe(eq => {
      this.equipment.set(eq);
      this.cdr.markForCheck();
    });
    this.api.getFeatureImportance().pipe(catchError(()=>of({features:[]}))).subscribe(r => {
      this.features.set(r.features ?? []);
      this.cdr.markForCheck();
    });
    this.load();
  }

  ngAfterViewInit() { this.viewReady = true; }
  ngOnDestroy()     { this.destroyCharts(); }

  /* ── actions ── */
  setPeriod(k: string) { this.selPeriod.set(k); if (k !== 'custom') this.load(); }

  selectMachine(code: string|null) { this.selMachine.set(code); this.load(); }

  load() {
    const p   = this.periods.find(x => x.key === this.selPeriod());
    const from = this.selPeriod()==='custom' ? this.customFrom : (p?.from ?? '2005-01-01');
    const to   = this.selPeriod()==='custom' ? this.customTo   : (p?.to   ?? '2005-12-31');
    this.loading.set(true);
    this.destroyCharts();

    const code = this.selMachine();
    if (code) {
      this.api.getMachineProductivity(code, from, to)
        .pipe(catchError(() => of(null)))
        .subscribe((data: MachineProductivity | null) => {
          this.machineData.set(data);
          this.loading.set(false);
          this.cdr.markForCheck();
          setTimeout(() => this.drawAll(), 80);
        });
    } else {
      this.api.getGlobalProductivity(from, to)
        .pipe(catchError(() => of(null)))
        .subscribe((data: GlobalProductivity | null) => {
          this.globalData.set(data);
          this.loading.set(false);
          this.cdr.markForCheck();
          setTimeout(() => this.drawAll(), 80);
        });
    }
  }

  /* ── chart drawing ── */
  private rgba(hex: string, a: number) {
    const r=parseInt(hex.slice(1,3),16), g=parseInt(hex.slice(3,5),16), b=parseInt(hex.slice(5,7),16);
    return `rgba(${r},${g},${b},${a})`;
  }

  private baseOpts(yLabel='') {
    return {
      responsive:true, maintainAspectRatio:false,
      plugins:{ legend:{display:false} },
      scales:{
        x:{ ticks:{color:'#64748b',font:{size:10},maxTicksLimit:14}, grid:{color:'#181d2e'} },
        y:{ ticks:{color:'#64748b',font:{size:10}, callback:(v:any)=>yLabel?v+yLabel:v},
            grid:{color:'#181d2e'} }
      }
    } as any;
  }

  private drawAll() {
    const s = this.series();
    if (!s.length) return;
    const labels = s.map(d => d.date.slice(5));

    this.drawProd(s, labels);
    this.drawPct(s, labels);
    this.drawEnergy(s, labels);
    this.drawCumul(s, labels);
    if (!this.selMachine() && this.globalData()) {
      this.drawBar();
      this.drawDonut();
    }
    this.drawFeat();
  }

  private makeChart(ref: ElementRef|undefined, type: any, data: any, opts: any): Chart|null {
    if (!ref?.nativeElement) return null;
    const c = new Chart(ref.nativeElement.getContext('2d')!, {type, data, options:opts});
    this.charts.push(c);
    return c;
  }

  private drawProd(s: DailyProductivity[], labels: string[]) {
    this.makeChart(this.chartProdRef, 'bar', {
      labels,
      datasets:[
        {label:'Ideal',  data:s.map(d=>d.idealHours),  backgroundColor:this.rgba('#10b981',.22), borderColor:'#10b981', borderWidth:1},
        {label:'Actual', data:s.map(d=>d.actualHours), backgroundColor:this.rgba('#f59e0b',.5),  borderColor:'#f59e0b', borderWidth:1},
        {label:'Lost',   data:s.map(d=>d.lostHours),   backgroundColor:this.rgba('#ef4444',.75), borderColor:'#ef4444', borderWidth:1},
      ]
    }, {...this.baseOpts('h'), plugins:{legend:{display:true,labels:{color:'#c4cde0',font:{size:11},boxWidth:10}}}});
  }

  private drawPct(s: DailyProductivity[], labels: string[]) {
    const ctx = this.chartPctRef?.nativeElement?.getContext('2d');
    if (!ctx) return;
    const grad = ctx.createLinearGradient(0,0,0,200);
    grad.addColorStop(0, this.rgba('#f59e0b',.4));
    grad.addColorStop(1, this.rgba('#f59e0b',.0));
    this.makeChart(this.chartPctRef, 'line', {
      labels,
      datasets:[{data:s.map(d=>d.productivityPct), borderColor:'#f59e0b',
        backgroundColor:grad, borderWidth:2, fill:true, tension:.35, pointRadius:0, pointHoverRadius:4}]
    }, {...this.baseOpts('%'), scales:{...this.baseOpts('%').scales, y:{...this.baseOpts('%').scales.y, min:60, max:100}}});
  }

  private drawEnergy(s: DailyProductivity[], labels: string[]) {
    this.makeChart(this.chartEnergyRef, 'bar', {
      labels,
      datasets:[
        {label:'Consumed', data:s.map(d=>d.energyConsumedKwh), backgroundColor:this.rgba('#f59e0b',.5), borderColor:'#f59e0b', borderWidth:1},
        {label:'Wasted',   data:s.map(d=>d.energyWastedKwh),   backgroundColor:this.rgba('#ef4444',.8), borderColor:'#ef4444', borderWidth:1},
      ]
    }, {...this.baseOpts('kWh'), plugins:{legend:{display:true,labels:{color:'#c4cde0',font:{size:11},boxWidth:10}}}});
  }

  private drawCumul(s: DailyProductivity[], labels: string[]) {
    let cum = 0;
    const cdata = s.map(d => { cum += d.energyWastedKwh; return +cum.toFixed(2); });
    const ctx = this.chartCumulRef?.nativeElement?.getContext('2d');
    if (!ctx) return;
    const grad = ctx.createLinearGradient(0,0,0,200);
    grad.addColorStop(0, this.rgba('#ef4444',.5));
    grad.addColorStop(1, this.rgba('#ef4444',.02));
    this.makeChart(this.chartCumulRef, 'line', {
      labels,
      datasets:[{data:cdata, borderColor:'#ef4444', backgroundColor:grad,
        borderWidth:2, fill:true, tension:.25, pointRadius:0}]
    }, this.baseOpts('kWh'));
  }

  private drawBar() {
    const bm = this.globalData()?.productivityByMachine ?? {};
    const sorted = Object.entries(bm).sort((a,b)=>a[1]-b[1]);
    this.makeChart(this.chartBarRef, 'bar', {
      labels: sorted.map(x=>x[0]),
      datasets:[{
        data: sorted.map(x=>x[1]),
        backgroundColor: sorted.map(x => x[1]<90?this.rgba('#ef4444',.7):x[1]<98?this.rgba('#f59e0b',.7):this.rgba('#10b981',.7)),
        borderWidth:0, borderRadius:3,
      }]
    }, {
      ...this.baseOpts('%'), indexAxis:'y' as const,
      scales:{...this.baseOpts('%').scales, x:{...this.baseOpts('%').scales.x,min:60,max:100,
        ticks:{callback:(v:any)=>v+'%',color:'#64748b',font:{size:10}}}}
    });
  }

  private drawDonut() {
    const bc = this.globalData()?.productivityByCategory ?? {};
    const COLORS = ['#10b981','#f59e0b','#3b82f6','#ef4444','#8b5cf6','#06b6d4',
                    '#f97316','#84cc16','#ec4899','#a78bfa','#14b8a6','#fb923c'];
    this.makeChart(this.chartDonutRef, 'doughnut', {
      labels: Object.keys(bc),
      datasets:[{data:Object.values(bc), backgroundColor:COLORS,
        borderColor:'#111420', borderWidth:2}]
    }, {
      responsive:true, maintainAspectRatio:false,
      plugins:{legend:{position:'right',labels:{color:'#c4cde0',font:{size:10},boxWidth:10}}}
    });
  }

  private drawFeat() {
    const f = this.features().slice(0,10);
    if (!f.length) return;
    this.makeChart(this.chartFeatRef, 'bar', {
      labels: f.map(x => x.feature.replace(/_/g,' ')),
      datasets:[{data:f.map(x=>x.importance),
        backgroundColor:this.rgba('#8b5cf6',.65), borderColor:'#8b5cf6',
        borderWidth:1, borderRadius:3}]
    }, {
      ...this.baseOpts(), indexAxis:'y' as const,
      scales:{...this.baseOpts().scales,
        x:{...this.baseOpts().scales.x, ticks:{color:'#64748b',font:{size:10}}}}
    });
  }

  private destroyCharts() { this.charts.forEach(c=>c.destroy()); this.charts=[]; }

  track(_: number, eq: Equipment) { return eq.machineCode; }
}
