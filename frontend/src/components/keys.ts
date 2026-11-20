import type { KeyboardEvent } from 'react';

/** Enter or Space runs the action, like on a real button, for elements that act as a link or button. */
export function onEnter(action: () => void) {
  return (e: KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      action();
    }
  };
}
