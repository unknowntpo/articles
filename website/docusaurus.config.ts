import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';

const config: Config = {
  title: 'Known, Unknown, Unknowable',
  tagline: 'A blog by unknowntpo',
  favicon: 'img/favicon.ico',

  future: {
    v4: true,
  },

  url: 'https://blog.unknowntpo.me',
  baseUrl: '/',

  organizationName: 'unknowntpo',
  projectName: 'articles',

  onBrokenLinks: 'throw',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  stylesheets: [
    {
      href: 'https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css',
      type: 'text/css',
      integrity:
        'sha384-nB0miv6/jRmo5YZBER8cLToXe5EsA0abguMSi/uyKMd/7TGs70cg5Ql4JvDEDYhT',
      crossorigin: 'anonymous',
    },
  ],

  presets: [
    [
      'classic',
      {
        docs: false,
        blog: {
          showReadingTime: true,
          blogSidebarCount: 'ALL',
          blogSidebarTitle: 'All Posts',
          remarkPlugins: [remarkMath],
          rehypePlugins: [rehypeKatex],
          feedOptions: {
            type: ['rss', 'atom'],
            xslt: true,
          },
          onInlineTags: 'warn',
          onInlineAuthors: 'warn',
          onUntruncatedBlogPosts: 'ignore',
        },
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
      title: 'Known, Unknown, Unknowable',
      items: [
        {to: '/blog', label: 'Blog', position: 'left'},
        {to: '/blog/tags', label: 'Tags', position: 'left'},
        {
          href: 'https://github.com/unknowntpo',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Blog',
          items: [
            {
              label: 'All Posts',
              to: '/blog',
            },
            {
              label: 'Tags',
              to: '/blog/tags',
            },
          ],
        },
        {
          title: 'Links',
          items: [
            {
              label: 'GitHub',
              href: 'https://github.com/unknowntpo',
            },
          ],
        },
      ],
      copyright: `Copyright © 2019-${new Date().getFullYear()} unknowntpo. Licensed under CC BY-NC 4.0. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['go', 'rust', 'haskell', 'sql', 'toml', 'bash'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
