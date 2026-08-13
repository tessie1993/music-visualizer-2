# Kotlin Coding Style

Applies to `**/*.kt`. Subordinate to
`musicviz-project/musicviz/docs/v2/MASTER_PLAN.md`; where they conflict, the
master plan wins.

## Formatting

- **ktlint** and **detekt** are configured in Gradle and enforced in CI.
- Official Kotlin code style (`kotlin.code.style=official`).

## Immutability — and its one exception

- Prefer `val`; use `data class` value types and copy-on-write state updates
  (`state.copy(field = newValue)`); expose immutable collections in public APIs.
- **Exception:** real-time audio, analysis, simulation and render hot paths
  deliberately reuse preallocated mutable buffers to avoid per-frame
  allocation. Do not "fix" these into immutable style — the master plan's
  performance gates require zero steady-state allocation after warm-up.

## Naming

- `camelCase` functions/properties, `PascalCase` types, `SCREAMING_SNAKE_CASE`
  constants.
- Name interfaces for behavior (`Clickable`), never `IClickable`.

## Null safety

- Never `!!` — prefer `?.`, `?:`, `requireNotNull()`, or `checkNotNull()`.
- Return nullable types from functions that legitimately have no result.

## Sealed types

Model closed state spaces as `sealed interface`/`sealed class` with exhaustive
`when` and **no `else` branch**, so a new variant fails compilation rather than
at runtime.

```kotlin
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
```

If two fields can contradict each other (`isLoading` + `error` + `data` all
nullable), replace them with one sealed state.

## Error handling

- Expected failures are values: return `Result<T>` or a sealed result type.
  Reserve thrown exceptions for bugs.
- Never catch `CancellationException` — always rethrow it.
- No `Any` in public signatures, no unchecked casts, no warning suppressions to
  get code past the compiler.

## Scope functions

`let` for null-check-and-transform, `run` to compute from a receiver, `apply` to
configure, `also` for side effects. Avoid nesting beyond two levels.
