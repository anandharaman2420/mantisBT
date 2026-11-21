import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.loggedIn) return router.createUrlTree(['/login']);
  if (!auth.user()) {
    try { await auth.bootstrap(); } catch { auth.logout(); return false; }
  }
  return true;
};

export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isAdmin() ? true : inject(Router).createUrlTree(['/']);
};

export const developerOrAdminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isAdmin() || (auth.user()?.accessLevel ?? 0) >= 55 ? true : inject(Router).createUrlTree(['/']);
};
