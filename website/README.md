# LocalS3 website

The website of LocalS3, built with [Docusaurus](https://docusaurus.io/) and TypeScript.

The documentation pages are read from [`../docs`](../docs), the only source of the documentation; this directory holds
the landing page, the sidebar and the theme. Links from the docs to files outside `docs/` are rewritten to GitHub.

```shell
npm install
npm start          # dev server at http://localhost:3000/local-s3/
npm run build      # static site in build/
npm run typecheck
```
