import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import Keycloak from 'keycloak-js';
import { App } from './app';
import { AUTH, authInterceptor } from './auth';
async function start() {
  const response = await fetch('/config.json');
  if (!response.ok) throw new Error('Configuration unavailable');
  const auth = new Keycloak(await response.json());
  await auth.init({ onLoad: 'check-sso', pkceMethod: 'S256', checkLoginIframe: false });
  await bootstrapApplication(App, { providers: [
    { provide: AUTH, useValue: auth }, provideHttpClient(withInterceptors([authInterceptor]))
  ] });
}
start().catch(() => { document.body.textContent = 'The sign-in service is starting or unavailable. Wait a moment, then refresh this page.'; });
