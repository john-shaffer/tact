# --jobs option

`--jobs N` (`-j N`) runs up to N scenarios at once.
Each scenario's output is printed in full once it finishes, in the order the scenarios were given.

`--jobs` greater than 1 cannot be combined with `--dir`, since scenarios would share the directory.
