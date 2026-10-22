import { ApiError } from './errors'
import { newSlug } from './id'

const SLUG_ATTEMPTS = 10

const INSERT_SHARE_SQL = `INSERT INTO shares (slug, note_id, user_id, password_hash, expires_at, views, created_at)
     VALUES (?1, ?2, ?3, ?4, ?5, 0, ?6)
     ON CONFLICT(note_id) DO UPDATE SET
       password_hash = CASE WHEN ?7 = 1 THEN excluded.password_hash ELSE shares.password_hash END,
       expires_at = CASE WHEN ?8 = 1 THEN excluded.expires_at ELSE shares.expires_at END
     WHERE shares.user_id = excluded.user_id`

export interface ShareInsert {
  noteId: string
  userId: string
  passwordHash: string | null
  expiresAt: number | null
  replacePassword: boolean
  replaceExpiry: boolean
}

/** Inserts or updates the share row, retrying with a fresh slug when the generated one is taken. */
export async function insertShare(db: D1Database, input: ShareInsert): Promise<boolean> {
  for (let attempt = 0; attempt < SLUG_ATTEMPTS; attempt++) {
    try {
      const written = await db
        .prepare(INSERT_SHARE_SQL)
        .bind(
          newSlug(),
          input.noteId,
          input.userId,
          input.passwordHash,
          input.expiresAt,
          Date.now(),
          input.replacePassword ? 1 : 0,
          input.replaceExpiry ? 1 : 0,
        )
        .run()
      return Boolean(written.meta.changes)
    } catch (err) {
      if (!isSlugCollision(err)) throw err
    }
  }
  throw new ApiError(503, 'internal', 'Could not allocate a share link. Please try again.')
}

function isSlugCollision(err: unknown): boolean {
  const message = err instanceof Error ? err.message : String(err ?? '')
  return /UNIQUE constraint failed: shares\.slug/i.test(message)
}
