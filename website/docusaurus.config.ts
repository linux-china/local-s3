import path from 'node:path';
import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

const repoUrl = 'https://github.com/linux-china/local-s3';

const config: Config = {
  title: 'LocalS3',
  tagline: 'A fast, lightweight Amazon S3 mock for testing and local development',
  favicon: 'img/favicon.svg',

  future: {
    v4: true,
  },

  url: 'https://locals3.googol-plex.ai/',
  baseUrl: '',

  organizationName: 'linux-china',
  projectName: 'local-s3',

  onBrokenLinks: 'throw',

  markdown: {
    // docs/*.md are plain GitHub Markdown shared with the repository, so parse .md as CommonMark, not MDX.
    format: 'detect',
    hooks: {
      // The docs also link to READMEs and examples outside docs/, which only exist on GitHub.
      onBrokenMarkdownLinks: ({sourceFilePath, url}) => {
        const target = path.posix.normalize(path.posix.join(path.posix.dirname(sourceFilePath), url));
        if (target.startsWith('../')) {
          return `${repoUrl}/blob/main/${target.slice('../'.length)}`;
        }
        throw new Error(`Broken Markdown link ${url} in ${sourceFilePath}`);
      },
    },
  },

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          // docs/*.md at the repository root is the only source of the documentation.
          path: '../docs',
          sidebarPath: './sidebars.ts',
          editUrl: `${repoUrl}/edit/main/docs/`,
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    colorMode: {
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'LocalS3',
      logo: {
        alt: 'LocalS3 Logo',
        src: 'img/logo.svg',
      },
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Docs',
        },
        {to: '/docs/cheatsheet', label: 'Cheat sheet', position: 'left'},
        {to: '/docs/apis', label: 'APIs', position: 'left'},
        {
          href: 'https://plugins.jetbrains.com/plugin/35345-local-s3',
          label: 'Local S3 JetBrains',
          position: 'right',
        },
        {
          href: 'https://hub.docker.com/r/linuxchina/local-s3',
          label: 'Docker Hub',
          position: 'right',
        },
        {
          href: repoUrl,
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {label: 'Cheat sheet', to: '/docs/cheatsheet'},
            {label: 'Embedding', to: '/docs/embedding'},
            {label: 'Deployment', to: '/docs/deployment'},
            {label: 'Data tools', to: '/docs/data-tools'},
          ],
        },
        {
          title: 'Reference',
          items: [
            {label: 'Supported APIs', to: '/docs/apis'},
            {label: 'Semantics', to: '/docs/semantics'},
            {label: 'Architecture', to: '/docs/architecture'},
            {label: 'Choosing an S3 mock', to: '/docs/comparison'},
          ],
        },
        {
          title: 'More',
          items: [
            {label: 'GitHub', href: repoUrl},
            {label: 'Docker Hub', href: 'https://hub.docker.com/r/linuxchina/local-s3'},
            {label: 'Maven Central', href: 'https://central.sonatype.com/namespace/org.mvnsearch'},
            {label: 'Changelog', href: `${repoUrl}/blob/main/CHANGELOG.md`},
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} LocalS3 contributors. Apache License 2.0. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['java', 'bash', 'python', 'sql', 'yaml', 'properties', 'toml', 'json', 'groovy', 'kotlin'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
