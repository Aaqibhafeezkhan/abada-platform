// Interaction sounds for the Abada login pages, matching Studio. cuelume
// (vendor/cuelume, MIT) synthesizes them in the browser with Web Audio: no
// audio files and no network requests. Keycloak renders every page from
// templates this theme inherits, so controls are marked here rather than in
// the markup. Password fields never play.
import { bind, play } from './vendor/cuelume/index.js';

const mark = (selector, attribute, value = '') => {
  document.querySelectorAll(selector).forEach((element) => {
    if (!element.hasAttribute(attribute)) element.setAttribute(attribute, value);
  });
};

mark('input[type="submit"], button[type="submit"]', 'data-cuelume-tap');
mark('input[type="text"], input[type="email"], input:not([type])', 'data-cuelume-type');
mark('#kc-form-options a, #kc-registration a, #kc-info a, .kc-social-links a, #kc-locale a', 'data-cuelume-navigate');
mark('#kc-form-options a, #kc-registration a, #kc-info a', 'data-cuelume-emphasis', 'subtle');
mark('input[type="checkbox"]', 'data-cuelume-toggle');

bind(document);

// A rejected sign-in reloads the page with an error alert. Browsers may still
// block audio on the fresh page; cuelume then stays silent.
if (document.querySelector('.alert-error')) {
  play('error', { emphasis: 'subtle' });
}
