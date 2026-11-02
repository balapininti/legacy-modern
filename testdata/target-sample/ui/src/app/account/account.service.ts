import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';

@Injectable({ providedIn: 'root' })
export class AccountApi {
  constructor(private http: HttpClient) {}

  view(id: number) {
    return this.http.get(`/api/accounts/${id}`);
  }
}
