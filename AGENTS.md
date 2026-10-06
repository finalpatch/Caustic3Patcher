This is the sister project of ../recaustic. recaustic reverse engineers the Caustic3 Android app and develops enhancement mods. In this project, we produce an Android patcher app that takes the upstream apk and offer to patch it with a number of optional mods and repackage it into a patched app.

See the other .md files for more details.

# Code-change review workflow

- Before editing code, explain the proposed approach and confirm it with the user. Wait for approval before implementation. Read-only investigation may proceed to inform the proposal. Confirm material changes to the approved approach.
- After implementation, provide a concise file-by-file summary: list each added, modified, or deleted file and explain what changed in it. Include relevant validation results and remaining limitations.
- Do not use large terminal diffs as the confirmation mechanism. The user will inspect diffs independently when needed. Small diffs (under 20 lines) may be displayed instead of a prose description when they communicate the change more clearly.
- Once an approach is approved, complete the agreed work without requesting approval again for individual edits within that scope.
