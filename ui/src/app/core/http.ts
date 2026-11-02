import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { Auth } from './auth.service';

/** Adds the bearer token to the API's own calls, and ends the session when the API refuses it. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(Auth);
  const router = inject(Router);
  const token = auth.token();
  const isOurs = req.url.startsWith('/api/');
  const r = isOurs && token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
  return next(r).pipe(
    catchError((e: HttpErrorResponse) => {
      // During sign-in a 401 is the answer to show, not an ended session.
      if (isOurs && e.status === 401 && auth.me() && !req.url.startsWith('/api/auth/')) auth.expired(router.url);
      return throwError(() => e);
    }),
  );
};

export const signedIn: CanActivateFn = (_route, state) => {
  const auth = inject(Auth);
  return auth.me() ? true : inject(Router).createUrlTree(['/login'], { queryParams: state.url === '/' ? {} : { return: state.url } });
};

/** The API enforces the roles; this keeps people away from pages that would only answer 403. */
export function requireRole(...roles: string[]): CanActivateFn {
  return () => {
    const auth = inject(Auth);
    return auth.has(...roles) ? true : inject(Router).createUrlTree(['/not-allowed']);
  };
}
