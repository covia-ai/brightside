# Security Policy

Brightside is a personal agent that runs a complete Covia venue inside its own process, on the owner's own computer, under the owner's own identity. It holds the owner's identity seed, conversations, memory and skills locally. We take security reports seriously and appreciate the time researchers spend to make it safer.

## Reporting a vulnerability

**Please do not open a public issue, discussion or pull request for a security vulnerability.**

Report privately through one of:

- **GitHub Private Vulnerability Reporting** (preferred): on this repository, go to the **Security** tab, then **Report a vulnerability**. The report and our coordination stay private until a fix is ready.
- **Email**: [security@covia.ai](mailto:security@covia.ai).

Please include, as far as you can:

- a description of the issue and its impact;
- the Brightside version, or the commit (`git rev-parse HEAD`), and your operating system;
- steps to reproduce, or a proof of concept;
- any suggested remediation.

## What to expect

- **Acknowledgement** within **3 business days**.
- An initial **assessment and severity** within **10 business days**.
- Regular updates while we work on a fix, and credit in the release notes once it ships, unless you prefer to stay anonymous.
- **Coordinated disclosure:** we agree a disclosure timeline with you and publish an advisory when a fix is available. Please give us reasonable time to remediate before going public.

We will not pursue legal action against researchers who act in good faith, avoid privacy violations and service disruption, and follow this policy.

## Supported versions

Brightside is an early preview. Security fixes land on `develop` and ship in the next release; we do not backport to older builds.

| Version | Supported |
|---------|-----------|
| `develop` / latest release | ✅ |
| Older releases | ⚠️ Upgrade to the latest |

## Scope

**In scope**: vulnerabilities in this repository, including:

- onboarding and recovery: the identity seed, recovery phrase, passphrase and vault;
- the embedded venue's wiring and what it stores on disk (`venue.etch`, keys, tokens for local tools);
- how the agent acts under the owner's identity, including the skills it writes for itself and the self-improvement loop that edits its own checkout;
- anything that leaves the machine: model calls, peer connections, or data sent anywhere the owner did not ask for.

**Out of scope here**, with where to report instead:

- the Covia runtime itself: [covia-ai/covia](https://github.com/covia-ai/covia/blob/develop/SECURITY.md);
- the upstream [Convex](https://github.com/Convex-Dev/convex) lattice platform;
- model providers, MCP servers and peers the owner chooses to connect;
- the host operating system, and skills the owner installed from elsewhere. If Brightside makes those easy to misuse, we still want to hear about it.

If you are unsure where a problem lives, report it to us and we will route it.

## Security properties as implemented

[docs/SECURITY.md](docs/SECURITY.md) describes the vault, the recovery invariant, the loopback-only venue and the separation of principals as implemented. It is the reference for reviewers and for anyone testing whether an installation can be recovered.
