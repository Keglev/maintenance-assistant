import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** Application shell: a header and the routed view. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly title = 'maintenance-assistant';
}
