# NETO-Daily

A daily-use AI assistant built with:

- Android
- Web
- Supabase
- Gemini

## Architecture

Android/Web
    ↓
Supabase
    ↓
NETO-Daily Edge Functions
    ↓
Gemini

## Security

- Gemini API key stays server-side.
- Supabase secret keys stay server-side.
- Client apps use the Supabase publishable key.
- User data is protected by Row Level Security.
