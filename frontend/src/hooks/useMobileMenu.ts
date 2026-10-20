import { useCallback, useEffect, useRef, useState } from 'react';

export function useMobileMenu() {
  const [isOpen, setIsOpen] = useState(false);
  const isOpenRef = useRef(false);

  const openMenu = useCallback(() => {
    if (isOpenRef.current) return;
    const currentState = history.state;
    const state = currentState && typeof currentState === 'object' ? currentState : {};
    history.pushState({ ...state, mobileMenu: true }, '');
    isOpenRef.current = true;
    setIsOpen(true);
  }, []);

  const closeMenu = useCallback(() => {
    if (!isOpenRef.current) return;
    setIsOpen(false);
    if (history.state?.mobileMenu) {
      history.back();
    } else {
      isOpenRef.current = false;
    }
  }, []);

  useEffect(() => {
    const onPopState = (event: PopStateEvent) => {
      if (!isOpenRef.current) return;
      isOpenRef.current = false;
      setIsOpen(false);
      event.stopImmediatePropagation();
    };

    window.addEventListener('popstate', onPopState, true);
    return () => window.removeEventListener('popstate', onPopState, true);
  }, []);

  return { isOpen, openMenu, closeMenu };
}
