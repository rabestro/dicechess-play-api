// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import mermaid from 'astro-mermaid';

// GitHub Pages project site: https://rabestro.github.io/dicechess-play-api
// (same host/base pattern the engine and analytics docs sites use — see ADR-0012).
export default defineConfig({
	site: 'https://rabestro.github.io',
	base: '/dicechess-play-api',
	integrations: [
		mermaid(),
		starlight({
			title: 'Dice Chess Bot API',
			description:
				'Connect a bot to the Dice Chess play platform — REST, streaming, or a single serverless webhook. Anonymous in minutes, provably-fair dice, an automatic rating ladder.',
			logo: { src: './src/assets/logo.svg', alt: 'Dice Chess Bot API' },
			favicon: '/favicon.svg',
			customCss: ['./src/styles/theme.css'],
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/rabestro/dicechess-play-api' },
			],
			editLink: {
				baseUrl: 'https://github.com/rabestro/dicechess-play-api/edit/main/docs/',
			},
			lastUpdated: true,
			sidebar: [
				{ label: 'Overview', link: '/' },
				{
					label: 'Get Started',
					items: [
						{ label: 'A Bot in Five Minutes', link: '/quickstart/' },
						{ label: 'Authentication & Identity', link: '/authentication/' },
					],
				},
				{
					label: 'Playing',
					items: [
						{ label: 'Game Mechanics', link: '/game-mechanics/' },
						{ label: 'Connection Modes', link: '/connection-modes/' },
						{ label: 'Provably-Fair Dice', link: '/provably-fair/' },
					],
				},
				{
					label: 'API Reference',
					items: [
						{ label: 'REST Endpoints', link: '/reference/rest/' },
						{ label: 'Event Streams', link: '/reference/streaming/' },
						{ label: 'Webhooks', link: '/reference/webhooks/' },
						{ label: 'Data Shapes', link: '/reference/data-shapes/' },
					],
				},
			],
		}),
	],
});
