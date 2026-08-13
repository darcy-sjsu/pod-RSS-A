import React, { useEffect, useState } from 'react';
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Divider,
  FileInput,
  Group,
  Modal,
  Stack,
  Switch,
  Text,
} from '@mantine/core';
import { IconCookie, IconRefresh, IconShieldCheck } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { formatDateWithPattern } from '../helpers/utils.js';
import { useDateFormat } from '../hooks/useDateFormat.js';

const COOKIE_ORDER = ['YOUTUBE'];
const COOKIE_INSTRUCTIONS_URL =
  'https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies';

const SESSION_STATUS_COLORS = {
  ACTIVE: 'darkgreen',
  STALE: 'orange',
  INVALID: 'red',
  UNKNOWN: 'gray',
};

function getCookieLabel(t) {
  return t('cookie_platform_youtube', { defaultValue: 'YouTube' });
}

function formatTimestamp(value, dateFormat) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const pad = (part) => String(part).padStart(2, '0');
  const datePart = formatDateWithPattern(date, dateFormat);
  const timePart = `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
  return `${datePart} ${timePart}`;
}

function getSessionStatusLabel(t, status) {
  const normalized = String(status || 'UNKNOWN').toUpperCase();
  const labels = {
    ACTIVE: t('cookie_session_status_active', { defaultValue: 'Session active' }),
    STALE: t('cookie_session_status_stale', { defaultValue: 'Refresh failing' }),
    INVALID: t('cookie_session_status_invalid', { defaultValue: 'Session expired' }),
    UNKNOWN: t('cookie_session_status_unknown', { defaultValue: 'Not checked yet' }),
  };
  return labels[normalized] || labels.UNKNOWN;
}

export default function CookieConfigModal({
  opened,
  onClose,
  cookieConfigs,
  onUpload,
  onDelete,
  onRefresh,
  onVerify,
  onToggleAutoRefresh,
}) {
  const { t } = useTranslation();
  const dateFormat = useDateFormat();
  const [fileByPlatform, setFileByPlatform] = useState({});
  const [uploadingPlatform, setUploadingPlatform] = useState('');
  const [deletingPlatform, setDeletingPlatform] = useState('');
  const [refreshingPlatform, setRefreshingPlatform] = useState('');
  const [verifyingPlatform, setVerifyingPlatform] = useState('');
  const [togglingPlatform, setTogglingPlatform] = useState('');

  useEffect(() => {
    if (opened) return;
    setFileByPlatform({});
    setUploadingPlatform('');
    setDeletingPlatform('');
    setRefreshingPlatform('');
    setVerifyingPlatform('');
    setTogglingPlatform('');
  }, [opened]);

  const cookieMap = new Map(
    (cookieConfigs || []).map((config) => [String(config?.platform || '').toUpperCase(), config]),
  );

  async function handleSelectFile(platform, file) {
    setFileByPlatform((current) => ({
      ...current,
      [platform]: file || null,
    }));

    if (!file) return;

    setUploadingPlatform(platform);
    await onUpload(platform, file);
    setUploadingPlatform('');

    setFileByPlatform((current) => ({
      ...current,
      [platform]: null,
    }));
  }

  async function handleDelete(platform) {
    setDeletingPlatform(platform);
    const isSuccess = await onDelete(platform);
    setDeletingPlatform('');

    if (!isSuccess) return;
    setFileByPlatform((current) => ({
      ...current,
      [platform]: null,
    }));
  }

  async function handleRefresh(platform) {
    setRefreshingPlatform(platform);
    await onRefresh(platform);
    setRefreshingPlatform('');
  }

  async function handleVerify(platform) {
    setVerifyingPlatform(platform);
    await onVerify(platform);
    setVerifyingPlatform('');
  }

  async function handleToggleAutoRefresh(platform, enabled) {
    setTogglingPlatform(platform);
    await onToggleAutoRefresh(platform, enabled);
    setTogglingPlatform('');
  }

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      size="lg"
      title={t('platform_cookies', { defaultValue: 'Platform Cookies' })}
    >
      <Stack>
        <Alert>
          <Stack gap={6}>
            <Text c="red" size="sm" fw={500}>
              {t('platform_cookie_warning', {
                defaultValue:
                  'Using account cookies may cause temporary or permanent restrictions. Use them only when necessary and prefer a throwaway account if possible.',
              })}
            </Text>
            <Text size="sm">
              {t('cookie_session_export_guide', {
                defaultValue:
                  'Sign in from a private window, open youtube.com/robots.txt, export the cookies and close the window immediately. Never browse YouTube with that session again. Chrome now binds cookies to the device, so prefer Firefox for the export.',
              })}
            </Text>
            <Anchor
              href={COOKIE_INSTRUCTIONS_URL}
              target="_blank"
              rel="noreferrer"
              size="sm"
              style={{ width: 'fit-content' }}
            >
              {t('platform_cookie_instructions_link', {
                defaultValue: 'See instructions on how to export cookies',
              })}
            </Anchor>
          </Stack>
        </Alert>

        {COOKIE_ORDER.map((platform) => {
          const summary = cookieMap.get(platform);
          const isConfigured = Boolean(summary);
          const sessionStatus = String(summary?.sessionStatus || 'UNKNOWN').toUpperCase();
          const isBusy =
            uploadingPlatform === platform ||
            deletingPlatform === platform ||
            refreshingPlatform === platform ||
            verifyingPlatform === platform;
          const platformLabel = getCookieLabel(t);
          const lastRotatedAt = formatTimestamp(summary?.lastRotatedAt, dateFormat);
          const nextRotateAt = formatTimestamp(summary?.nextRotateAt, dateFormat);
          const updatedAt = formatTimestamp(summary?.updatedAt, dateFormat);

          return (
            <Stack key={platform} gap="sm">
              <Stack gap={6}>
                <Group gap="xs">
                  <Text fw={600}>{platformLabel}</Text>
                  <Badge color={isConfigured ? 'darkgreen' : 'gray'} variant="outline">
                    {isConfigured
                      ? t('platform_cookie_status_configured', { defaultValue: 'Configured' })
                      : t('platform_cookie_status_not_configured', {
                          defaultValue: 'Not configured',
                        })}
                  </Badge>
                  {isConfigured ? (
                    <Badge color={SESSION_STATUS_COLORS[sessionStatus] || 'gray'} variant="light">
                      {getSessionStatusLabel(t, sessionStatus)}
                    </Badge>
                  ) : null}
                </Group>
                <Text size="sm" c="dimmed">
                  {t('platform_cookie_youtube_description', {
                    defaultValue:
                      'Use YouTube cookies for age-restricted, members-only, or other risk-controlled content.',
                  })}
                </Text>
                {updatedAt ? (
                  <Text size="sm" fs="italic" c="darkgreen">
                    {t('platform_cookie_updated_at', {
                      time: updatedAt,
                      defaultValue: 'Updated: {{time}}',
                    })}
                  </Text>
                ) : null}
                {lastRotatedAt ? (
                  <Text size="sm" c="dimmed">
                    {t('cookie_session_last_rotated_at', {
                      time: lastRotatedAt,
                      defaultValue: 'Last refreshed: {{time}}',
                    })}
                  </Text>
                ) : null}
                {nextRotateAt ? (
                  <Text size="sm" c="dimmed">
                    {t('cookie_session_next_rotate_at', {
                      time: nextRotateAt,
                      defaultValue: 'Next refresh: {{time}}',
                    })}
                  </Text>
                ) : null}
                {summary?.lastFailureReason ? (
                  <Text size="sm" c="orange">
                    {t('cookie_session_last_failure_reason', {
                      reason: summary.lastFailureReason,
                      defaultValue: 'Last failure: {{reason}}',
                    })}
                  </Text>
                ) : null}
                {sessionStatus === 'INVALID' ? (
                  <Text size="sm" c="red">
                    {t('cookie_session_invalid_hint', {
                      defaultValue:
                        'This session is no longer valid. Export and upload fresh cookies using the instructions above.',
                    })}
                  </Text>
                ) : null}
              </Stack>

              {isConfigured ? (
                <Stack gap="xs">
                  <Switch
                    label={t('cookie_session_auto_refresh', { defaultValue: 'Automatic refresh' })}
                    description={t('cookie_session_auto_refresh_hint', {
                      defaultValue:
                        'The backend keeps the session alive on the interval YouTube declares.',
                    })}
                    checked={summary?.autoRefreshEnabled !== false}
                    disabled={togglingPlatform === platform}
                    onChange={(event) => {
                      handleToggleAutoRefresh(platform, event.currentTarget.checked).then();
                    }}
                  />
                  <Group>
                    <Button
                      variant="default"
                      leftSection={<IconRefresh size={14} />}
                      loading={refreshingPlatform === platform}
                      disabled={isBusy && refreshingPlatform !== platform}
                      onClick={() => handleRefresh(platform)}
                    >
                      {t('cookie_session_refresh_now', { defaultValue: 'Refresh now' })}
                    </Button>
                    <Button
                      variant="default"
                      leftSection={<IconShieldCheck size={14} />}
                      loading={verifyingPlatform === platform}
                      disabled={isBusy && verifyingPlatform !== platform}
                      onClick={() => handleVerify(platform)}
                    >
                      {t('cookie_session_verify', { defaultValue: 'Verify sign-in' })}
                    </Button>
                  </Group>
                </Stack>
              ) : null}

              <Divider />

              <Group align="flex-end" wrap="nowrap">
                <FileInput
                  label={t('platform_cookie_file_label', {
                    platform: platformLabel,
                    defaultValue: '{{platform}} Cookies File',
                  })}
                  placeholder={t('select_file')}
                  accept="text/plain"
                  value={fileByPlatform[platform] || null}
                  onChange={(file) => {
                    handleSelectFile(platform, file).then();
                  }}
                  leftSection={<IconCookie size={16} />}
                  disabled={isBusy}
                  style={{ flex: 1 }}
                />
                <Button
                  variant="default"
                  onClick={() => handleDelete(platform)}
                  disabled={!isConfigured || (isBusy && deletingPlatform !== platform)}
                  loading={deletingPlatform === platform}
                >
                  {t('platform_cookie_clear', { defaultValue: 'Clear Uploaded Cookies' })}
                </Button>
              </Group>
            </Stack>
          );
        })}
      </Stack>
    </Modal>
  );
}
