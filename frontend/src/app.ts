import { Component, inject, signal, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AUTH } from './auth';
interface Customer { id: string; reference: string; fullName: string; email: string; currency: string; status: string; }
interface FinancialInput { monthlyIncome: number; monthlyDebtPayments: number; creditUtilizationPercent: number; missedPaymentsLast12Months: number; creditHistoryMonths: number; recentCreditInquiries: number; currency: string; }
interface Result { score: number; riskBand: string; algorithmVersion: string; contributions: Record<string, number>; observations: string[]; }
interface Score { id: string; sourceEventId: string; sourceVersion: number; calculatedAt: string; result: Result; }
interface Report { id: string; scoreId: string; sourceVersion: number; generatedAt: string; result: Result; }
interface Page<T> { items: T[]; total: number; }
interface Audit { id: string; actorId: string; action: string; resourceId: string; occurredAt: string; }
type Tab = 'Overview' | 'Financial data' | 'History' | 'Reports' | 'Audit';
@Component({ selector: 'credit-app', standalone: true, imports: [CommonModule, FormsModule], templateUrl: './app.html' })
export class App implements OnDestroy {
  readonly auth = inject(AUTH); private http = inject(HttpClient);
  readonly signedIn = !!this.auth.authenticated;
  readonly admin = this.auth.hasRealmRole('ADMIN');
  readonly username = this.auth.tokenParsed?.['preferred_username'] || 'Officer';
  readonly tabs: Tab[] = this.admin ? ['Overview','Financial data','History','Reports','Audit'] : ['Overview','Financial data','History','Reports'];
  customers = signal<Customer[]>([]); total = signal(0); page = signal(0); selected = signal<Customer | null>(null);
  tab = signal<Tab>('Overview'); latest = signal<Score | null>(null); history = signal<Score[]>([]); reports = signal<Report[]>([]);
  error = signal(''); notice = signal(''); loading = signal(false); submitting = signal(false); pending = signal(false);
  showNew = signal(false); creating = signal(false); audit = signal<Audit[]>([]); auditService = 'users'; auditPage = signal(0); auditTotal = signal(0);
  historyPage = signal(0); historyTotal = signal(0); reportPage = signal(0); reportTotal = signal(0);
  search = ''; draft = { reference: '', fullName: '', email: '', currency: 'INR' };
  input: FinancialInput = this.sample('INR'); private epoch = 0; private searchEpoch = 0;
  private interval?: ReturnType<typeof setTimeout>; private submissionKey?: string; private submissionPayload?: string;
  constructor() { if (this.signedIn) void this.loadCustomers(); }
  login() { void this.auth.login({ redirectUri: location.origin + '/' }); }
  logout() { void this.auth.logout({ redirectUri: location.origin + '/' }); }
  ngOnDestroy() { this.stopPolling(); }
  private sample(currency: string): FinancialInput { return { monthlyIncome: 80000, monthlyDebtPayments: 12000, creditUtilizationPercent: 25, missedPaymentsLast12Months: 0, creditHistoryMonths: 72, recentCreditInquiries: 1, currency }; }
  useSample() { this.input = this.sample(this.selected()?.currency || 'INR'); }
  initials(name: string) { return name.split(' ').slice(0,2).map(n => n[0]).join(''); }
  errorText(e: unknown) { return e instanceof HttpErrorResponse ? (e.error?.detail || e.error?.message || (e.status === 401 ? 'Your session expired. Sign in again.' : 'The request could not be completed. Please retry.')) : 'The request could not be completed. Please retry.'; }
  async loadCustomers(page = 0) {
    const version = ++this.searchEpoch; this.loading.set(true); this.error.set('');
    try { const r = await firstValueFrom(this.http.get<Page<Customer>>('/api/customers', { params: { search: this.search, page, size: 10 } }));
      if(version !== this.searchEpoch) return; this.customers.set(r.items); this.total.set(r.total); this.page.set(page);
      if (!this.selected() && r.items.length) await this.select(r.items[0]);
    } catch(e) { if(version === this.searchEpoch) this.error.set(this.errorText(e)); }
    finally { if(version === this.searchEpoch) this.loading.set(false); }
  }
  async select(c: Customer) {
    this.stopPolling(); this.epoch++; this.selected.set(c); this.tab.set('Overview'); this.latest.set(null); this.history.set([]); this.reports.set([]);
    this.historyPage.set(0); this.reportPage.set(0); this.historyTotal.set(0); this.reportTotal.set(0);
    this.error.set(''); this.notice.set(''); this.input = this.sample(c.currency); this.submissionKey = undefined;
    await this.refresh(this.epoch);
  }
  private async refresh(version: number) {
    const c = this.selected(); if(!c) return;
    try {
      const [history, reports] = await Promise.all([
        firstValueFrom(this.http.get<Page<Score>>(`/api/scores/customers/${c.id}/history`, {params:{page:0}})),
        firstValueFrom(this.http.get<Page<Report>>(`/api/reports/customers/${c.id}`, {params:{page:0}}))
      ]);
      if(version !== this.epoch) return;
      this.latest.set(history.items[0] || null); this.history.set(history.items); this.historyTotal.set(history.total); this.historyPage.set(0);
      this.reports.set(reports.items); this.reportTotal.set(reports.total); this.reportPage.set(0);
    } catch(e) { if(version === this.epoch) this.error.set(this.errorText(e)); }
  }
  async changeTab(tab: Tab) { this.tab.set(tab); if(tab === 'Audit') await this.loadAudit(); }
  async createCustomer() {
    if(this.creating()) return; this.creating.set(true); this.error.set('');
    try { const c = await firstValueFrom(this.http.post<Customer>('/api/customers', this.draft));
      this.showNew.set(false); this.search = ''; await this.loadCustomers(); await this.select(c);
      this.draft = {reference:'',fullName:'',email:'',currency:'INR'}; this.notice.set('Customer profile created. Add financial data to calculate a score.');
    } catch(e) { this.error.set(this.errorText(e)); } finally { this.creating.set(false); }
  }
  async submit() {
    const c = this.selected(); if(!c || this.submitting() || this.pending()) return;
    const version = this.epoch; this.error.set(''); this.notice.set(''); this.submitting.set(true);
    const payload = JSON.stringify(this.input);
    if(!this.submissionKey || this.submissionPayload !== payload) this.submissionKey = crypto.randomUUID();
    this.submissionPayload = payload;
    try { const event = await firstValueFrom(this.http.post<{eventId:string}>(`/api/data/customers/${c.id}`, this.input, {headers:{'Idempotency-Key':this.submissionKey}}));
      if(version !== this.epoch) return;
      this.pending.set(true); this.notice.set('Financial data saved. Calculating the score and preparing the report…');
      void this.poll(event.eventId, version, Date.now() + 90000);
    } catch(e) { if(version === this.epoch) this.error.set(this.errorText(e)); }
    finally { this.submitting.set(false); }
  }
  private async poll(eventId: string, version: number, deadline: number) {
    if(version !== this.epoch) return; await this.refresh(version); if(version !== this.epoch) return;
    const score = this.history().find(s => s.sourceEventId === eventId);
    if(score && this.reports().some(r => r.scoreId === score.id)) {
      this.pending.set(false); this.submissionKey = undefined; this.notice.set('Analysis complete. Your score and report are ready.'); this.tab.set('Overview'); return;
    }
    if(Date.now() > deadline) { this.pending.set(false); this.notice.set('Processing is taking longer than expected. Refresh the results before submitting again.'); return; }
    this.interval = setTimeout(() => void this.poll(eventId, version, deadline), 2000);
  }
  private stopPolling() { clearTimeout(this.interval); this.pending.set(false); }
  async refreshResults() { await this.refresh(this.epoch); }
  async historyNext(page: number) {
    const c = this.selected(); if(!c) return; const version = this.epoch;
    try { const r = await firstValueFrom(this.http.get<Page<Score>>(`/api/scores/customers/${c.id}/history`, {params:{page}})); if(version === this.epoch) {this.history.set(r.items); this.historyPage.set(page); this.historyTotal.set(r.total);} }
    catch(e) { if(version === this.epoch) this.error.set(this.errorText(e)); }
  }
  async reportsNext(page: number) {
    const c = this.selected(); if(!c) return; const version = this.epoch;
    try { const r = await firstValueFrom(this.http.get<Page<Report>>(`/api/reports/customers/${c.id}`, {params:{page}})); if(version === this.epoch) {this.reports.set(r.items); this.reportPage.set(page); this.reportTotal.set(r.total);} }
    catch(e) { if(version === this.epoch) this.error.set(this.errorText(e)); }
  }
  async download(r: Report) {
    try { const blob = await firstValueFrom(this.http.get(`/api/reports/${r.id}/pdf`,{responseType:'blob'}));
      const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = `credit-report-${r.id}.pdf`; link.click(); setTimeout(()=>URL.revokeObjectURL(url),1000);
    } catch(e) { this.error.set(this.errorText(e)); }
  }
  async email(r: Report) {
    const c = this.selected(); if(!c) return;
    try { await firstValueFrom(this.http.post(`/api/reports/${r.id}/email`,{to:c.email})); this.notice.set('Report summary sent to the local demo inbox at localhost:8025.'); }
    catch(e) { this.error.set(this.errorText(e)); }
  }
  async loadAudit(page = 0) {
    try { const r = await firstValueFrom(this.http.get<{content:Audit[];totalElements:number}>(`/api/admin/${this.auditService}/audit`,{params:{page,size:20}})); this.audit.set(r.content); this.auditPage.set(page); this.auditTotal.set(r.totalElements); }
    catch(e) { this.error.set(this.errorText(e)); }
  }
  factors() { return Object.entries(this.latest()?.result.contributions || {}).map(([name,value]) => ({name,value,max:({'Payment history':200,'Credit utilization':150,'Debt affordability':100,'History length':75,'Recent inquiries':25} as Record<string,number>)[name] || 100})); }
}
