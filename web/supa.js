// The only file that imports supabase-js. The version is pinned (RULES 4): bump it on purpose, never
// by dropping the version from the URL. Kept apart from app.js so the rest can be tested without a network.
import { createClient } from 'https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.119.2/+esm';

export function makeClient(url, anonKey) {
  return createClient(url, anonKey, {
    // The session lives in localStorage so a reload keeps you signed in. Nothing is read from the URL.
    auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: false },
  });
}
