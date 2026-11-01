import { InjectionToken, inject } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';
import Keycloak from 'keycloak-js';
import { from, switchMap } from 'rxjs';
export const AUTH = new InjectionToken<Keycloak>('AUTH');
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AUTH);
  if (!request.url.startsWith('/api/')) return next(request);
  return from(auth.updateToken(30)).pipe(switchMap(() => next(request.clone({
    setHeaders: { Authorization: `Bearer ${auth.token}`, 'X-Request-ID': crypto.randomUUID() }
  }))));
};
