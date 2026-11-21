import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/** adds the Bearer token; on 401 (expired token) goes back to the login page */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.token;
  const r = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
  return next(r).pipe(catchError((e: HttpErrorResponse) => {
    if (e.status === 401 && !req.url.endsWith('/auth/login')) auth.logout();
    return throwError(() => e);
  }));
};

export function errMsg(e: any): string {
  return e?.error?.message || (e?.status === 0 ? 'Cannot reach the server' : e?.message) || 'Request failed';
}
