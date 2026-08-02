<!-- Thanks for contributing. See CONTRIBUTING.md for the conventions this plugin follows. -->

### What does this change?

<!-- A sentence or two. Link the issue if there is one. -->

### Checklist

- [ ] `mvn clean verify` passes locally (not just `mvn test` — SpotBugs and HPI packaging only run in `verify`)
- [ ] New or changed behaviour is covered by a test
- [ ] If file handling changed, it was exercised on **both** Windows and Linux
- [ ] No replacement value can reach a log, exception message or `toString()`
- [ ] The `engine` package still has no Jenkins imports
- [ ] A release-drafter label is set on this PR, so the release notes categorise it correctly
- [ ] `README.md` updated if the change alters documented behaviour or limitations
- [ ] An ADR added or updated if this changes a design decision

### Anything reviewers should look at closely?

<!-- Platform-specific behaviour, security-relevant paths, or anything you were unsure about. -->
