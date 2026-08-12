import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import zh from './locales/zh.json';

const APP_LANGUAGE = 'zh';

localStorage.setItem('language', APP_LANGUAGE);

i18n
  .use(initReactI18next)
  .init({
    resources: {
      zh: { translation: zh },
    },
    lng: APP_LANGUAGE,
    fallbackLng: APP_LANGUAGE,
    supportedLngs: [APP_LANGUAGE],
    nonExplicitSupportedLngs: true,
    load: 'languageOnly',
    interpolation: { escapeValue: false },
  })
  .then();

export default i18n;
