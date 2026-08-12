import React, { useContext } from 'react';
import {
  ActionIcon,
  Group,
  Image,
  Menu,
  Paper,
  Text,
  useComputedColorScheme,
  useMantineColorScheme,
} from '@mantine/core';
import logo from '../assets/pigeonpod.svg';
import {
  IconLogout2,
  IconMoon,
  IconSettings,
  IconSun,
} from '@tabler/icons-react';
import { useMediaQuery } from '@mantine/hooks';
import { API, showSuccess } from '../helpers/index.js';
import { useNavigate } from 'react-router-dom';
import { UserContext } from '../context/User/UserContext.jsx';
import { useTranslation } from 'react-i18next';

function Header() {
  const isSmallScreen = useMediaQuery('(max-width: 36em)');
  const computedColorScheme = useComputedColorScheme();
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  const toggleColorScheme = () => {
    setColorScheme(colorScheme === 'dark' ? 'light' : 'dark');
  };
  const contextValue = useContext(UserContext);
  const state = Array.isArray(contextValue) ? contextValue[0] : (contextValue?.state || contextValue);
  const dispatch = Array.isArray(contextValue) ? contextValue[1] : (contextValue?.dispatch || (() => null));
  const navigate = useNavigate();
  const { t } = useTranslation();

  async function logout() {
    await API.post('/api/auth/logout');
    dispatch({ type: 'logout' });
    localStorage.removeItem('user');
    showSuccess(t('logout_success'));
    navigate('/login');
  }

  return (
    <Paper shadow="xs" p={5} pos="sticky" style={{ top: 0, zIndex: 100, position: 'sticky' }}>
      <Group justify="space-between" mx={isSmallScreen ? 'xs' : 'xl'}>
        <Group gap="xs" mr={10} onClick={() => navigate('/')} style={{ cursor: 'pointer' }}>
          <Image src={logo} w={40} referrerPolicy="no-referrer"></Image>
          {/*<Title order={4}>{t('header_title')}</Title>*/}
        </Group>
        <Group>
          <ActionIcon variant="default" size="sm">
            {'dark' === computedColorScheme ? (
              <IconSun onClick={toggleColorScheme} />
            ) : (
              <IconMoon onClick={toggleColorScheme} />
            )}
          </ActionIcon>
          {state.user ? (
            <Menu withArrow>
              <Menu.Target>
                <Group gap={0} style={{ cursor: 'pointer' }}>
                  <Text fw={600}>{state.user.username}</Text>
                </Group>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Item
                  leftSection={<IconSettings size={14} />}
                  onClick={() => navigate('/user-setting')}
                >
                  {t('header_account')}
                </Menu.Item>
                {state.authEnabled ? (
                  <Menu.Item leftSection={<IconLogout2 size={14} />} onClick={logout}>
                    {t('header_logout')}
                  </Menu.Item>
                ) : null}
              </Menu.Dropdown>
            </Menu>
          ) : (
            <></>
          )}
        </Group>
      </Group>
    </Paper>
  );
}

export default Header;
