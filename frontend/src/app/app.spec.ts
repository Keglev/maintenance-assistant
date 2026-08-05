import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
  });

  it('creates the app shell', () => {
    const fixture = TestBed.createComponent(App);

    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders the application title in the header', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    const header = (fixture.nativeElement as HTMLElement).querySelector('.app-title');

    expect(header?.textContent).toContain('maintenance-assistant');
  });
});
