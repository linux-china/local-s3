import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

const sidebars: SidebarsConfig = {
  docsSidebar: [
    'cheatsheet',
    {
      type: 'category',
      label: 'Guides',
      collapsed: false,
      items: ['embedding', 'jetbrains-plugin', 'deployment', 'data-tools'],
    },
    {
      type: 'category',
      label: 'Reference',
      collapsed: false,
      items: ['apis', 'semantics', 'architecture'],
    },
    'comparison',
  ],
};

export default sidebars;
