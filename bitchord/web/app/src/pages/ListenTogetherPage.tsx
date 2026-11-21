import { useMutation, useQuery } from '@tanstack/react-query';
import { Check, Copy, Crown, LogOut, Radio, Send, Smartphone, UserMinus } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router';
import { PageHeader } from '@/components/PageHeader';
import { useSignedIn } from '@/data/music/queries';
import { cleanCode, inviteUrl } from '@/party/invite';
import {
  controlsLocked,
  createParty,
  DEFAULT_SERVER,
  fetchSyncState,
  idleServer,
  joinParty,
  leaveParty,
  messageOf,
  party,
  partyServer,
  partySettings,
  previewParty,
  savePartySettings,
  shareJamToDiscord,
  useParty,
} from '@/party/listenTogether';
import { usePlayer } from '@/state/player';
import { Toggle } from './IntegrationSettings';
import { settingsUi } from './settingsStyles';

const lede = 'mx-4 mb-4 type-body-tall text-secondary';
const error = 'mx-4 mt-3 type-callout text-red';
const primary = 'flex-none rounded-pill bg-key-bg px-4 py-1.5 type-body-emphasized text-white disabled:opacity-45';
// The code, large, as the phone shows it.
const invite = 'flex flex-col items-center gap-2.5 rounded-[10px] bg-quinary px-4 py-7 text-center';
const inviteCode = 'type-[700_40px/48px] tracking-[0.18em]';
const WEBHOOK_STORAGE_KEY = 'aurex:discord-webhook';

/**
 * Listen Together (ListenTogetherScreen on the phone): start a party or join
 * one by code, sync active Jam rooms between Aurex Android and Aurex Web,
 * and broadcast rich Jam invites directly to Discord channels.
 */
export default function ListenTogetherPage() {
  const params = useParams();
  const [search] = useSearchParams();
  const inviteCode = params.code ? cleanCode(params.code) : null;
  const inviteServer = search.get('server');
  const state = useParty();
  const signedIn = useSignedIn();

  return (
    <div className={settingsUi.centered}>
      <PageHeader title="Listen Together & Discord Jam" />
      <div className={settingsUi.body}>
        {state.code ? (
          <InParty />
        ) : inviteCode ? (
          <InviteCard code={inviteCode} server={inviteServer} signedIn={signedIn} />
        ) : (
          <StartOrJoin signedIn={signedIn} />
        )}
        {state.error && <p className={error}>{state.error}</p>}
      </div>
    </div>
  );
}

function GuestModeTip() {
  return (
    <p className={`${settingsUi.footer} mb-4`}>
      Not signed into YouTube Music in the extension — Aurex will use your party nickname below to sync with your Galaxy S24+ and friends.
    </p>
  );
}

function MobileWebSyncBanner({ currentCode }: { currentCode: string | null }) {
  const serverBase = idleServer();
  const [copiedServer, setCopiedServer] = useState(false);
  const sync = useQuery({
    queryKey: ['party', 'syncState', serverBase],
    queryFn: () => fetchSyncState(serverBase),
    refetchInterval: 3000,
    retry: false,
  });
  const joinActive = useMutation({
    mutationFn: ({ code, server }: { code: string; server: string }) => joinParty(code, server),
  });

  useEffect(() => {
    if (!copiedServer) return;
    const t = setTimeout(() => setCopiedServer(false), 1500);
    return () => clearTimeout(t);
  }, [copiedServer]);

  const activeCode = sync.data?.activePartyCode;
  const activeServer = sync.data?.serverUrl || serverBase;
  const trackLabel = sync.data?.currentTrack?.title
    ? `${sync.data.currentTrack.title}${sync.data.currentTrack.artist ? ` — ${sync.data.currentTrack.artist}` : ''}`
    : null;

  return (
    <>
      <h2 className={settingsUi.sectionHeader}>Aurex Mobile ↔ Web Sync</h2>
      <section className={settingsUi.group}>
        <div className={settingsUi.row}>
          <span className={settingsUi.rowText}>
            <span className="inline-flex items-center gap-1.5">
              <Smartphone size={15} />
              <span>Sync Server</span>
            </span>
            <span className={settingsUi.rowDetail}>{serverBase || 'Not configured'}</span>
          </span>
          {serverBase && (
            <button
              className={settingsUi.inlineButton}
              onClick={() => void navigator.clipboard.writeText(serverBase).then(() => setCopiedServer(true))}
            >
              {copiedServer ? 'Copied' : 'Copy URL'}
            </button>
          )}
        </div>
        {activeCode && activeCode !== currentCode && (
          <div className={settingsUi.row}>
            <span className={settingsUi.rowText}>
              <span className="inline-flex items-center gap-1.5">
                <Radio size={15} />
                <span>Active Phone Jam ({activeCode})</span>
              </span>
              <span className={settingsUi.rowDetail}>
                {trackLabel ?? `${sync.data?.updatedBy || 'Aurex Device'} is listening right now`}
              </span>
            </span>
            <button
              className={primary}
              disabled={joinActive.isPending}
              onClick={() => joinActive.mutate({ code: activeCode, server: activeServer })}
            >
              {joinActive.isPending ? 'Joining…' : `Join Active Phone Jam (${activeCode})`}
            </button>
          </div>
        )}
      </section>
    </>
  );
}

function StartOrJoin({ signedIn }: { signedIn: boolean }) {
  const navigate = useNavigate();
  const [code, setCode] = useState('');
  const [settings, setSettings] = useState(partySettings);
  const autoplay = usePlayer((s) => s.autoplay);
  const create = useMutation({ mutationFn: () => createParty({ autoplayEnabled: autoplay }) });
  const update = (change: Partial<typeof settings>) => {
    savePartySettings(change);
    setSettings(partySettings());
  };

  return (
    <>
      <p className={lede}>
        Listen to the same music at the same moment across your Galaxy S24+, Aurex Web, and Discord friends. Anyone in the party can play, pause, and queue tracks.
      </p>
      {!signedIn && <GuestModeTip />}
      <section className={settingsUi.group}>
        <div className={settingsUi.row}>
          <span className={settingsUi.rowText}>
            <span>Start a Party</span>
            <span className={settingsUi.rowDetail}>Generates a 6-character code for Mobile, Web & Discord.</span>
          </span>
          <button className={primary} disabled={create.isPending} onClick={() => create.mutate()}>
            {create.isPending ? 'Starting…' : 'Start'}
          </button>
        </div>
        <form
          className={settingsUi.controlRow}
          onSubmit={(e) => {
            e.preventDefault();
            const cleaned = cleanCode(code);
            if (cleaned) navigate(`/invite/${cleaned}`);
          }}
        >
          <span>Join with a Code</span>
          <span className="flex gap-2">
            <input
              className="w-[110px] flex-none rounded-[8px] border-0 bg-quaternary px-2.5 py-[5px] text-center type-body tracking-[0.12em] text-primary uppercase"
              value={code}
              maxLength={7}
              placeholder="ABC123"
              aria-label="Party code"
              onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
            />
            <button className={primary} type="submit" disabled={!cleanCode(code)}>
              Join
            </button>
          </span>
        </form>
      </section>

      <MobileWebSyncBanner currentCode={null} />

      <h2 className={settingsUi.sectionHeader}>You</h2>
      <section className={settingsUi.group}>
        <label className={settingsUi.controlRow}>
          <span>Name in parties</span>
          <input
            className={settingsUi.input}
            placeholder="Dhinesh SDK (Web)"
            value={settings.nickname}
            maxLength={80}
            onChange={(e) => update({ nickname: e.currentTarget.value })}
          />
        </label>
        <label className={settingsUi.controlRow}>
          <span>Party server</span>
          <input
            className={settingsUi.input}
            placeholder={DEFAULT_SERVER ? new URL(DEFAULT_SERVER).host : '192.168.1.13:8787'}
            value={settings.customServer}
            onChange={(e) => update({ customServer: e.currentTarget.value })}
          />
        </label>
      </section>
      <p className={settingsUi.footer}>Leave the server empty to use Aurex’s synced LAN/default server. Anyone joining your party uses the server it’s on.</p>

      <h2 className={settingsUi.sectionHeader}>Discord</h2>
      <section className={settingsUi.group}>
        <div className={settingsUi.row}>
          <span className={settingsUi.rowText}>
            <span>Discord Rich Presence</span>
            <span className={settingsUi.rowDetail}>Show what you’re listening to on your Discord profile and let friends join.</span>
          </span>
          <button className={settingsUi.inlineButton} onClick={() => navigate('/settings/discord')}>
            Open Discord Settings
          </button>
        </div>
      </section>
    </>
  );
}

/** An invite: who's in it, then a tap to join (the tap also lets the browser start audio). */
function InviteCard({ code, server, signedIn }: { code: string; server: string | null; signedIn: boolean }) {
  const preview = useQuery({ queryKey: ['party', 'preview', code, server], queryFn: () => previewParty(code, server), retry: false });
  const join = useMutation({ mutationFn: () => joinParty(code, server) });
  const data = preview.data;
  return (
    <div className={invite}>
      <p className={inviteCode}>{code}</p>
      {preview.isPending && <p className={settingsUi.footer}>Looking up the party…</p>}
      {preview.isError && <p className={error}>{messageOf(preview.error)}</p>}
      {data && (
        <>
          <p className={lede}>
            {data.hostName ? `${data.hostName}’s party` : 'An Aurex Listen Together party'}
            {data.memberCount ? ` · ${data.memberCount} of ${data.maxMembers} listening` : ''}
          </p>
          <div className="flex gap-1.5">
            {data.members.map((m, i) => (
              <Avatar key={i} name={m.displayName} url={m.avatarUrl} large />
            ))}
          </div>
        </>
      )}
      {!signedIn && <GuestModeTip />}
      <button
        className="mt-1.5 flex-none rounded-pill bg-key-bg px-7 py-2.5 type-title-3-emphasized text-white disabled:opacity-45"
        disabled={join.isPending || data?.isFull || preview.isError}
        onClick={() => join.mutate()}
      >
        {data?.isFull ? 'This party is full' : join.isPending ? 'Joining…' : 'Tap to Join'}
      </button>
    </div>
  );
}

function Avatar({ name, url, large = false }: { name: string; url: string | null; large?: boolean }) {
  const className = `grid place-items-center rounded-full bg-quaternary object-cover type-callout-emphasized ${large ? 'size-9' : 'size-6'}`;
  return url ? (
    <img className={className} src={url} alt="" referrerPolicy="no-referrer" />
  ) : (
    <span className={className}>{name.slice(0, 1).toUpperCase()}</span>
  );
}

function InParty() {
  const state = useParty();
  const navigate = useNavigate();
  const [copied, setCopied] = useState(false);
  const [copiedDiscordCard, setCopiedDiscordCard] = useState(false);
  const [webhookUrl, setWebhookUrl] = useState(() => localStorage.getItem(WEBHOOK_STORAGE_KEY) ?? '');
  const [discordNotice, setDiscordNotice] = useState<string | null>(null);

  const isHost = state.you?.isHost === true;
  const locked = controlsLocked(state);
  const effectiveServer = partyServer() || DEFAULT_SERVER;
  const custom = effectiveServer && effectiveServer !== DEFAULT_SERVER ? effectiveServer : null;
  const link = inviteUrl(state.code!, custom);
  const encodedServer = encodeURIComponent(effectiveServer);
  const webJoinUrl = `${window.location.origin}/invite/${state.code}?server=${encodedServer}`;
  const androidJoinUrl = `bitchord://party/${state.code}?server=${encodedServer}`;
  const nowTrackText = state.playback.track?.title
    ? `${state.playback.track.title}${state.playback.track.artist ? ` — ${state.playback.track.artist}` : ''}`
    : 'Ready to jam';

  const discordShare = useMutation({
    mutationFn: () =>
      shareJamToDiscord({
        webhookUrl,
        code: state.code!,
        serverUrl: effectiveServer,
        webUrl: window.location.origin,
        hostName: state.you?.displayName ?? 'Aurex Listener',
        track: state.playback.track,
      }),
    onSuccess: () => setDiscordNotice('Posted Jam invite embed to Discord channel!'),
    onError: (err) => setDiscordNotice(err instanceof Error ? err.message : 'Failed to post to Discord.'),
  });

  useEffect(() => {
    if (!copied) return;
    const t = setTimeout(() => setCopied(false), 1500);
    return () => clearTimeout(t);
  }, [copied]);

  useEffect(() => {
    if (!copiedDiscordCard) return;
    const t = setTimeout(() => setCopiedDiscordCard(false), 1500);
    return () => clearTimeout(t);
  }, [copiedDiscordCard]);

  const status =
    state.connection === 'live'
      ? state.clockSynced
        ? `In sync · ${state.roundTripMs} ms round trip`
        : 'Measuring the clock…'
      : state.connection === 'connecting'
        ? 'Reconnecting…'
        : 'Offline';

  const discordCardText = [
    `🎧 **Aurex Live Jam — Room \`${state.code}\`**`,
    `Playing: **${nowTrackText}**`,
    `Join on Web: ${webJoinUrl}`,
    `Join on Aurex Android: ${androidJoinUrl}`,
  ].join('\n');

  return (
    <>
      <div className={invite}>
        <p className={inviteCode}>{state.code}</p>
        <p className={settingsUi.footer}>{status}</p>
        <div className="flex flex-wrap items-center justify-center gap-2">
          <button
            className="inline-flex items-center gap-1.5 rounded-pill bg-quaternary px-3.5 py-1.5 type-callout-emphasized"
            onClick={() => void navigator.clipboard.writeText(link).then(() => setCopied(true))}
            title={link}
          >
            {copied ? <Check size={15} /> : <Copy size={15} />}
            {copied ? 'Copied Link' : 'Copy Invite Link'}
          </button>
          <button
            className="inline-flex items-center gap-1.5 rounded-pill bg-quaternary px-3.5 py-1.5 type-callout-emphasized"
            onClick={() => void navigator.clipboard.writeText(discordCardText).then(() => setCopiedDiscordCard(true))}
          >
            {copiedDiscordCard ? <Check size={15} /> : <Copy size={15} />}
            {copiedDiscordCard ? 'Copied Discord Card' : 'Copy Discord Jam Card'}
          </button>
        </div>
      </div>

      <h2 className={settingsUi.sectionHeader}>
        Listening · {state.members.length} of {state.maxMembers}
      </h2>
      <section className={settingsUi.group}>
        {state.members.map((m) => (
          <div key={m.memberId} className={settingsUi.integrationPlain}>
            <Avatar name={m.displayName} url={m.avatarUrl} />
            <span className={settingsUi.integrationName}>
              {m.displayName}
              {m.memberId === state.you?.memberId && <span className="text-secondary"> (you)</span>}
            </span>
            <span className={settingsUi.value}>
              {m.isHost && <Crown size={14} aria-label="Host" />}
              <span className="size-2 rounded-full bg-quaternary data-on:bg-green" data-on={m.connected || undefined} title={m.connected ? 'Connected' : 'Reconnecting'} />
              {isHost && m.memberId !== state.you?.memberId && (
                <button className={settingsUi.inlineButton} onClick={() => party.kick(m.memberId)} title="Remove from the party">
                  <UserMinus size={13} />
                </button>
              )}
            </span>
          </div>
        ))}
      </section>

      <h2 className={settingsUi.sectionHeader}>Discord Jam Broadcaster</h2>
      <section className={settingsUi.group}>
        <label className={settingsUi.controlRow}>
          <span>Channel Webhook</span>
          <input
            className={settingsUi.input}
            placeholder="https://discord.com/api/webhooks/..."
            value={webhookUrl}
            onChange={(e) => {
              const v = e.currentTarget.value;
              setWebhookUrl(v);
              localStorage.setItem(WEBHOOK_STORAGE_KEY, v);
            }}
          />
        </label>
        <div className={settingsUi.row}>
          <span className={settingsUi.rowText}>
            <span>Post Live Jam to Discord Channel</span>
            <span className={settingsUi.rowDetail}>{discordNotice ?? 'Sends an embed with room code & Web/Android links.'}</span>
          </span>
          <button
            className={primary}
            disabled={!webhookUrl.trim() || discordShare.isPending}
            onClick={() => {
              setDiscordNotice(null);
              discordShare.mutate();
            }}
          >
            <span className="inline-flex items-center gap-1.5">
              <Send size={13} />
              <span>{discordShare.isPending ? 'Sending…' : 'Send to Discord'}</span>
            </span>
          </button>
        </div>
        <div className={settingsUi.row}>
          <span className={settingsUi.rowText}>
            <span>Discord Rich Presence</span>
            <span className={settingsUi.rowDetail}>Connect your Discord account status & listening card.</span>
          </span>
          <button className={settingsUi.inlineButton} onClick={() => navigate('/settings/discord')}>
            Settings
          </button>
        </div>
      </section>

      <h2 className={settingsUi.sectionHeader}>Party</h2>
      <section className={settingsUi.group}>
        <Toggle
          label="AutoPlay"
          detail="Keep the party going with similar music when the queue runs out."
          checked={state.playback.autoplayEnabled}
          onChange={(on) => !locked && party.setAutoplay(on)}
        />
        {isHost && (
          <Toggle
            label="Only I control the music"
            detail="Others can listen but not play, pause, skip or change the queue."
            checked={state.hostOnlyControl}
            onChange={party.setHostOnlyControl}
          />
        )}
      </section>
      {locked && <p className={settingsUi.footer}>The host controls the music in this party. You can still pause your own device.</p>}

      {state.activity.length > 0 && (
        <>
          <h2 className={settingsUi.sectionHeader}>Activity</h2>
          <section className={settingsUi.group}>
            {state.activity.slice(0, 8).map((a, i) => (
              <div key={i} className={settingsUi.row}>
                <span className={settingsUi.rowText}>
                  <span>
                    {a.by} · {a.action}
                  </span>
                  {a.detail && <span className={settingsUi.rowDetail}>{a.detail}</span>}
                </span>
                <span className={settingsUi.rowDetail}>{new Date(a.atMs).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}</span>
              </div>
            ))}
          </section>
        </>
      )}

      <button className="mx-4 mt-6 inline-flex items-center gap-1.5 type-body-emphasized text-red" onClick={() => void leaveParty().then(() => navigate('/settings/listen-together'))}>
        <LogOut size={15} />
        Leave Party
      </button>
    </>
  );
}
