export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  confirmPassword: string;
}

export interface AuthResponse {
  message: string;
  username?: string;
  error?: string;
}

export const login = async (username: string, password: string): Promise<AuthResponse> => {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ username, password }),
  });
  const data = await res.json();
  return data;
};

export const register = async (username: string, password: string, confirmPassword: string): Promise<AuthResponse> => {
  const res = await fetch('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ username, password, confirmPassword }),
  });
  const data = await res.json();
  return data;
};

export const logout = async (): Promise<AuthResponse> => {
  const res = await fetch('/api/auth/logout', {
    method: 'POST',
    credentials: 'include',
  });
  const data = await res.json();
  return data;
};

export const getCurrentUser = async (): Promise<{ username: string } | null> => {
  try {
    const res = await fetch('/api/auth/me', {
      credentials: 'include',
    });
    if (!res.ok) return null;
    const data = await res.json();
    return data;
  } catch {
    return null;
  }
};
