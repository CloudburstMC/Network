# Writing for reviewers and implementers

Explain what changes, why it matters, and how the reader can check it. Assume the
reader understands software but has not followed this project's discussions.

- Start with the problem and the resulting behavior. Put audit details later.
- Name the actor and action: “the host checks the token,” for example.
- Give each sentence one main idea. Split sentences that ask the reader to hold
  several conditions in mind.
- Use familiar words. Keep a technical term when it adds precision, and explain
  it on first use.
- Show a small example before a complex rule. Use a table for comparisons or a
  sequence for a flow.
- Keep exact API names, field names, limits, and signing formats. Simpler prose
  must not weaken a protocol requirement.
- State what the tests establish and what remains untested. Support performance
  or reliability claims with measurements.
- Link to detailed history and build records. Keep attribution and dependency
  tables where reviewers need them, but avoid repeating full commit hashes in
  prose.

For a PR, lead with the behavior change, then explain the approach and relevant
validation. For an aggregate proposal, also show who contributed each part and
which future PRs depend on others. Describe the final change rather than the
sequence of attempts used to build it.

For a specification, start with its purpose, a short example, and the terms a
reader needs. Follow with the exact rules. Keep optional product behavior clearly
identified so an independent implementation knows what it needs to support.

| Before | After |
| --- | --- |
| “Old processes are fenced.” | “After activation, the provider rejects requests from the previous generation.” |
| “Bounded namespaced extensions.” | “Optional metadata uses named extensions with limits on their number and size.” |
| “Persistent endpoint identity.” | “The host reuses its DTLS certificate, so clients see the advertised fingerprint.” |

Before publishing, read the opening paragraph aloud. A reviewer should be able
to explain the benefit without first reading the implementation.

This checklist follows [Google's advice on short sentences](https://developers.google.com/tech-writing/one/short-sentences)
and [Microsoft's style and voice guidance](https://learn.microsoft.com/en-us/style-guide/top-10-tips-style-voice).
