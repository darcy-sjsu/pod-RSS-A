import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './locales/en.json';
import zh from './locales/zh.json';
import es from './locales/es.json';
import ja from './locales/ja.json';
import pt from './locales/pt.json';
import fr from './locales/fr.json';
import de from './locales/de.json';
import ko from './locales/ko.json';

const savedLng = localStorage.getItem('language') || 'en';

i18n
  .use(initReactI18next)
  .init({
    resources: {
      en: { translation: en },
      zh: { translation: zh },
      es: { translation: es },
      ja: { translation: ja },
      pt: { translation: pt },
      fr: { translation: fr },
      de: { translation: de },
      ko: { translation: ko },
    },
    lng: savedLng, // default language
    fallbackLng: 'en',
    interpolation: { escapeValue: false },
  })
  .then();

export default i18n;
