# The v0 install

A Brightside store written by a build from before Covia versioned its venue
state or its conversations, for `CoviaMigrationTest` to upgrade.

- Written by Brightside `54158a9` on Covia 0.9.11 (the release), by
  `V0Install` copied into that checkout.
- `venue.etch.zip` holds the store; its key comes from `V0Install.SEED`.
  Zipped because most of the file is unused space.
- `expected.txt` is what that build showed the owner, written by
  `V0Install.describe`, and read back the same after a restart there.

To make it again:

```
git worktree add --detach ../brightside-v0 54158a9
cp src/test/java/brightside/V0Install.java ../brightside-v0/src/test/java/brightside/
cd ../brightside-v0
mvn test -Dtest=V0Install -Dbrightside.v0.out=<empty directory>
```

then zip `venue.etch` and copy it and `expected.txt` here.
