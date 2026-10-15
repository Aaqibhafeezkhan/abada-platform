import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import mermaid from 'astro-mermaid';

export default defineConfig({
  site: 'https://abada-engine-docs.vercel.app',
  integrations: [
    mermaid({
      autoTheme: true,
      enableLog: false,
      mermaidConfig: {
        flowchart: { curve: 'basis' },
        sequence: { mirrorActors: false },
      },
    }),
    starlight({
      title: 'Abada Platform',
      description: 'The self-hosted runtime for governed AI-driven business processes: APL, Studio, the Agent Worker and Insight on a PostgreSQL-authoritative engine.',
      defaultLocale: 'root',
      locales: {
        root: {
          label: 'English',
          lang: 'en',
        },
        fr: {
          label: 'Français',
          lang: 'fr',
        },
      },
      favicon: '/favicon.svg',
      logo: {
        light: './src/assets/abada-mark-deep.svg',
        dark: './src/assets/abada-mark.svg',
      },
      lastUpdated: true,
      editLink: {
        baseUrl: 'https://github.com/bashizip/abada-platform/edit/dev/documentation/',
      },
      social: [
        {
          icon: 'github',
          label: 'GitHub',
          href: 'https://github.com/bashizip/abada-platform',
        },
      ],
      customCss: ['./src/styles/custom.css'],
      sidebar: [
        {
          label: 'Start here',
          translations: { fr: 'Pour commencer' },
          items: [
            { label: 'Documentation home', translations: { fr: 'Accueil de la documentation' }, slug: 'index' },
            { label: 'Concepts', slug: 'concepts' },
            { label: 'Guide scope and contracts', translations: { fr: 'Périmètre du guide et contrats' }, slug: 'scope' },
          ],
        },
        {
          label: 'Get started',
          translations: { fr: 'Démarrer' },
          items: [
            { label: 'Quickstart', translations: { fr: 'Démarrage rapide' }, slug: 'user/quickstart' },
            { label: 'Tutorial: a reviewed AI reply', translations: { fr: 'Tutoriel : une réponse IA relue' }, slug: 'user/first-workflow' },
            { label: 'Tutorial: an agent that acts', translations: { fr: 'Tutoriel : un agent qui agit' }, slug: 'user/tutorial-agent-that-acts' },
          ],
        },
        {
          label: 'Build processes',
          translations: { fr: 'Concevoir des processus' },
          items: [
            { label: 'Use cases', translations: { fr: 'Cas d’utilisation' }, slug: 'user/use-cases' },
            { label: 'Author processes in APL', translations: { fr: 'Créer des processus en APL' }, slug: 'user/authoring' },
            { label: 'Loops, routes and review decisions', translations: { fr: 'Boucles, parcours et décisions de revue' }, slug: 'user/process-patterns' },
            { label: 'Agent nodes and the Agent Worker', translations: { fr: 'Nœuds agent et Agent Worker' }, slug: 'user/agent-worker' },
          ],
        },
        {
          label: 'Walkthroughs',
          translations: { fr: 'Parcours guidés' },
          items: [
            { label: 'Overview', translations: { fr: 'Vue d’ensemble' }, slug: 'walkthroughs' },
            { label: 'The Agent Worker, its SDK, tools and MCP', translations: { fr: 'Agent Worker, son SDK, ses outils et MCP' }, slug: 'walkthroughs/agent-worker-and-sdk' },
            { label: 'A CRM call and back to the engine', translations: { fr: 'Appel CRM et retour vers le moteur' }, slug: 'walkthroughs/tool-call-round-trip' },
            { label: 'Many instances at once', translations: { fr: 'Plusieurs instances en parallèle' }, slug: 'walkthroughs/concurrency' },
            { label: 'A refund that needs approval', translations: { fr: 'Un remboursement qui nécessite une approbation' }, slug: 'walkthroughs/approval-refund' },
            { label: 'Delegating to a sub-process', translations: { fr: 'Déléguer à un sous-processus' }, slug: 'walkthroughs/delegation' },
            { label: 'Writing a third-party worker', translations: { fr: 'Écrire un worker tiers' }, slug: 'walkthroughs/third-party-worker' },
          ],
        },
        {
          label: 'Run and govern',
          translations: { fr: 'Exploiter et gouverner' },
          items: [
            { label: 'Operate running processes', translations: { fr: 'Exploiter les processus en cours' }, slug: 'user/operations' },
            { label: 'Insight: governed AI optimization', translations: { fr: 'Insight : optimisation de l’IA gouvernée' }, slug: 'user/studio-insight' },
            { label: 'Users, groups and project access', translations: { fr: 'Utilisateurs, groupes et accès aux projets' }, slug: 'user/studio-administration' },
            { label: 'Troubleshooting', translations: { fr: 'Dépannage' }, slug: 'user/troubleshooting' },
          ],
        },
        {
          label: 'Deploy the platform',
          translations: { fr: 'Déployer la plateforme' },
          items: [
            { label: 'Choose a deployment', translations: { fr: 'Choisir un déploiement' }, slug: 'user' },
            { label: 'Development deployment', translations: { fr: 'Déploiement de développement' }, slug: 'user/development' },
            { label: 'Production deployment', translations: { fr: 'Déploiement en production' }, slug: 'user/production' },
            { label: 'Production identity', translations: { fr: 'Identité en production' }, slug: 'user/identity' },
            { label: 'Single-VM server', translations: { fr: 'Serveur sur une seule VM' }, slug: 'user/server' },
            { label: 'Telemetry', translations: { fr: 'Télémétrie' }, slug: 'user/telemetry' },
            { label: 'Privacy and the install report', translations: { fr: 'Confidentialité et rapport d’installation' }, slug: 'user/privacy' },
            { label: 'Scaling', translations: { fr: 'Mise à l’échelle' }, slug: 'user/scaling' },
            { label: 'Backup and upgrades', translations: { fr: 'Sauvegardes et mises à niveau' }, slug: 'user/backup-upgrade' },
          ],
        },
        {
          label: 'Architecture',
          translations: { fr: 'Architecture' },
          items: [
            { label: 'Architecture overview', translations: { fr: 'Vue d’ensemble de l’architecture' }, slug: 'architecture' },
            { label: 'The transactional execution core', translations: { fr: 'Noyau d’exécution transactionnel' }, slug: 'architecture/runtime' },
            { label: 'Native APL and the canonical model', translations: { fr: 'APL natif et modèle canonique' }, slug: 'architecture/apl' },
            { label: 'Agent Worker execution', translations: { fr: 'Exécution de l’Agent Worker' }, slug: 'architecture/agent-worker' },
            { label: 'The Insight Engine', translations: { fr: 'Le moteur Insight' }, slug: 'architecture/insight' },
            { label: 'Platform deployment', translations: { fr: 'Déploiement de la plateforme' }, slug: 'architecture/deployment' },
            { label: 'Cluster execution', translations: { fr: 'Exécution en cluster' }, slug: 'architecture/cluster' },
            { label: 'Security model', translations: { fr: 'Modèle de sécurité' }, slug: 'architecture/security' },
          ],
        },
        {
          label: 'Compatibility & migration',
          translations: { fr: 'Compatibilité et migration' },
          items: [
            { label: 'BPMN import and compatibility', translations: { fr: 'Importation et compatibilité BPMN' }, slug: 'compatibility/bpmn' },
          ],
        },
        {
          label: 'Developer guide',
          translations: { fr: 'Guide du développeur' },
          items: [
            { label: 'Developer overview', translations: { fr: 'Vue d’ensemble du développement' }, slug: 'developer' },
            { label: 'Repository map', translations: { fr: 'Organisation du dépôt' }, slug: 'developer/repository' },
            { label: 'Engine changes', translations: { fr: 'Modifications du moteur' }, slug: 'developer/engine' },
            { label: 'Database changes', translations: { fr: 'Modifications de la base de données' }, slug: 'developer/database' },
            { label: 'API and worker contracts', translations: { fr: 'Contrats API et worker' }, slug: 'developer/contracts' },
            { label: 'Testing and verification', translations: { fr: 'Tests et vérification' }, slug: 'developer/testing' },
            { label: 'Writing documentation', translations: { fr: 'Rédiger la documentation' }, slug: 'developer/documentation' },
          ],
        },
        {
          label: 'Reference',
          translations: { fr: 'Référence' },
          items: [
            { label: 'Authoritative contracts', translations: { fr: 'Contrats de référence' }, slug: 'reference/contracts' },
          ],
        },
      ],
    }),
  ],
});
