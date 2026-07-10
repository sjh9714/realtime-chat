import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { AuthResponse } from '../types';

interface AuthState {
  session: AuthResponse | null;
  setSession: (session: AuthResponse) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      session: null,
      setSession: (session) => set({ session }),
      logout: () => set({ session: null }),
    }),
    {
      name: 'relay-auth',
      storage: createJSONStorage(() => sessionStorage),
    },
  ),
);
