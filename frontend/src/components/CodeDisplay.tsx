/**
 * A code the user reads off one screen and types into another: large, in groups of three or four
 * characters, so it can be read aloud and compared at a glance. Screen readers get it in one piece.
 */
export function CodeDisplay({ code, label }: { code: string; label: string }) {
  const plain = code.replace(/[^A-Za-z0-9]/g, '')
  const size = plain.length % 4 === 0 ? 4 : 3
  const grouped = (plain.match(new RegExp(`.{1,${size}}`, 'g')) ?? [plain]).join(' ')
  return (
    <div className="code-display">
      <span className="code-display__label">{label}</span>
      <span className="code-display__value" aria-label={`${label}: ${code}`}>
        {grouped}
      </span>
    </div>
  )
}
