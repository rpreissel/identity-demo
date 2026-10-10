/** What went wrong, as a sentence: an error's message, anything else as text. */
export function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
}
