# expected: content

The `content` key checks a file's content with exact string equality.

A file can have several assertions, such as `content` and `json-open`.
Each is checked and reported separately, labelled with the file path and the assertion, e.g. `data.json (content)`.
