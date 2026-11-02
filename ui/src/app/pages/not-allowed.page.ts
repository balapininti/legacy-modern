import { Component } from '@angular/core';

@Component({
  selector: 'app-not-allowed',
  template: `<h1>Not allowed</h1><p class="state-msg" data-testid="not-allowed">Your role does not allow this page. Ask an admin if you need it.</p>`,
})
export class NotAllowedPage {}
