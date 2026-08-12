import React, { useState } from 'react';
import {
  Modal,
  Text,
  TextInput,
  Button,
  Group,
  Stack,
  Alert,
  ActionIcon,
  Tooltip,
} from '@mantine/core';
import { IconCopy, IconCheck, IconAlertCircle } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

const CopyModal = ({ opened, onClose, text }) => {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (error) {
      console.error('复制失败:', error);
    }
  };

  const handleSelectAll = (event) => {
    event.target.select();
  };

  return (
    <Modal opened={opened} onClose={onClose} withCloseButton={false} size="lg">
      <Stack>
        <Alert
          icon={<IconAlertCircle size={16} />}
          title={t('manual_copy_alert_title')}
          color="orange"
        ></Alert>

        <Text size="sm" c="dimmed">
          {t('manual_copy_instruction')}
        </Text>

        <TextInput
          //   label={t('content_to_copy')}
          value={text}
          readOnly
          rightSection={
            <Tooltip label={copied ? t('copied') : t('click_to_copy')}>
              <ActionIcon
                variant="subtle"
                color={copied ? 'green' : 'blue'}
                onClick={handleCopy}
                onMouseDown={(e) => e.preventDefault()}
              >
                {copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
              </ActionIcon>
            </Tooltip>
          }
          onClick={handleSelectAll}
          styles={{
            input: {
              fontFamily: 'monospace',
              fontSize: '0.875rem',
              cursor: 'text',
            },
          }}
        />

        <Group justify="flex-end">
          <Button variant="outline" onClick={onClose}>
            {t('close')}
          </Button>
          <Button
            leftSection={<IconCopy size={16} />}
            onClick={handleCopy}
            color={copied ? 'green' : 'blue'}
          >
            {copied ? t('copied') : t('copy')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
};

export default CopyModal;
