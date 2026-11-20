# 0002. Use a React web frontend

- **Status:** Accepted
- **Date:** 2026-10-23

## Context

PocketLedger needs an interface for account forms, transaction entry, and history. Users may access it from different devices and operating systems. Maintaining separate interfaces for each platform would increase development and testing work.

## Decision

We will use a browser-based React 19 frontend with TypeScript and Vite, communicating with the Spring Boot backend through HTTP API requests. The implementation is defined in [package.json](../../frontend/package.json) and [App.tsx](../../frontend/src/App.tsx).

A web interface allows users on different operating systems to access the same application through a compatible browser without installing a dedicated app. The repository currently supports local development; production hosting has not been configured.

## Alternatives Considered

- **Native mobile apps:** Could provide closer device integration, but would require mobile tooling, installation, and platform testing for features currently handled through browser forms.
- **Plain HTML and JavaScript:** Would avoid React dependencies, but require more manual coordination of form state, selected tabs, loading messages, and transaction history updates.

## Consequences

Positive:

- One frontend can serve desktop and mobile browsers without separate platform applications.
- React manages changing interface state, while TypeScript checks data types during development.

Negative:

- React and Vite add dependencies and a build process.
- Browser compatibility and small-screen usability still require testing; offline use is not implemented.

Follow-up:

- Choose production hosting and configure the frontend's API address and backend CORS settings for it.
- Test account and transaction flows across supported browsers and screen sizes.
