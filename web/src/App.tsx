import { AuthScreen } from './components/AuthScreen';
import { ChatShell } from './components/ChatShell';
import { useAuthStore } from './stores/auth-store';

export function App() {
  const session = useAuthStore((state) => state.session);
  return session ? <ChatShell /> : <AuthScreen />;
}
