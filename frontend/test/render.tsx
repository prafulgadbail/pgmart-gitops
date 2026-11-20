import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '../src/App';
import type { Cart } from '../src/state/store';
import type { User } from '../src/types';
import { type Backend, startBackend, tokenFor } from './backend';

/** Opens the store at a page, as a guest or logged in, with an optional cart in the browser. */
export function openStore(path: string, opts: { as?: User; cart?: Cart; before?: (backend: Backend) => void } = {}) {
  const backend = startBackend();
  opts.before?.(backend);
  if (opts.as) localStorage.setItem('pgmart.token', tokenFor(opts.as));
  if (opts.cart) localStorage.setItem('pgmart.cart', JSON.stringify(opts.cart));
  window.history.pushState({}, '', `/shop${path}`);
  const user = userEvent.setup();
  render(<App />);
  return { backend, user };
}
