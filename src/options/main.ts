import { DEFAULT_SETTINGS, PRIVACY_PROMISE, SUPPORTED_SITES } from '../shared/constants';
import type {
  BackgroundResponse,
  InterventionKind,
  InterventionRecord,
  Settings,
  SiteId,
  SiteRule,
} from '../shared/types';

const INTERVENTION_ORDER: readonly InterventionKind[] = ['notify', 'pause', 'close-tab', 'block'];

const INTERVENTION_LABELS: Record<InterventionKind, string> = {
  notify: 'Notify me',
  pause: 'Show a pause screen',
  'close-tab': 'Close the tab',
  block: 'Block until tomorrow',
};

const NUMBER_FIELDS = [
  { field: 'dailyAllowanceMinutes', label: 'Daily allowance (minutes)', min: 1, max: 240 },
  { field: 'doomscrollBudgetMinutes', label: 'Doomscroll session budget (minutes)', min: 1, max: 60 },
  { field: 'warningScore', label: 'Warning score', min: 1, max: 50 },
  { field: 'gracePeriodSeconds', label: 'Grace period (seconds)', min: 0, max: 600 },
] as const;

type NumberField = (typeof NUMBER_FIELDS)[number]['field'];

/** Never throws: a worker that is not listening simply yields no data. */
async function request(message: unknown): Promise<BackgroundResponse | undefined> {
  const runtime = (
    globalThis as { chrome?: { runtime?: { sendMessage?: (value: unknown) => unknown } } }
  ).chrome?.runtime;
  if (!runtime?.sendMessage) return undefined;
  try {
    return (await runtime.sendMessage(message)) as BackgroundResponse | undefined;
  } catch {
    return undefined;
  }
}

function element<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  text?: string,
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  // Text content only — dynamic values never reach innerHTML.
  if (text !== undefined) node.textContent = text;
  return node;
}

function labelled(text: string, control: HTMLElement): HTMLLabelElement {
  const label = element('label');
  label.append(element('span', text), control);
  return label;
}

function cloneSettings(settings: Settings): Settings {
  const rules = {} as Record<SiteId, SiteRule>;
  for (const site of SUPPORTED_SITES) {
    const rule = settings.rules[site.id] ?? DEFAULT_SETTINGS.rules[site.id];
    rules[site.id] = { ...rule, interventions: [...rule.interventions] };
  }
  return { enabled: settings.enabled, rules };
}

function describe(rule: SiteRule): string {
  if (!rule.enabled) return 'Disabled — nothing is enforced for this site.';
  const steps = rule.interventions.map((kind) => INTERVENTION_LABELS[kind]).join(', then ');
  return `A declared doomscroll session gets ${rule.doomscrollBudgetMinutes} minutes. After ${rule.warningScore} confidence points, or ${rule.dailyAllowanceMinutes} minutes today: ${steps || 'nothing configured'}. Grace period ${rule.gracePeriodSeconds}s.`;
}

export async function renderOptions(root: Element): Promise<void> {
  const status = await request({ type: 'get-status' });
  const loaded =
    status && status.ok && status.type === 'status' ? status.settings : DEFAULT_SETTINGS;

  // Edits live in this draft until Save; the persisted rules are untouched.
  const draft = cloneSettings(loaded);

  root.replaceChildren();

  const form = element('form');
  form.noValidate = true;

  const heading = element('h1', 'Local Focus Coach');
  const promise = element('p', PRIVACY_PROMISE);
  promise.className = 'privacy';

  const globalToggle = element('input');
  globalToggle.type = 'checkbox';
  globalToggle.checked = draft.enabled;
  globalToggle.addEventListener('change', () => {
    draft.enabled = globalToggle.checked;
  });

  form.append(heading, promise, labelled('Protection enabled', globalToggle));

  const summaries = new Map<SiteId, HTMLElement>();
  const errors = new Map<SiteId, HTMLElement>();

  function refresh(site: SiteId): void {
    const summary = summaries.get(site);
    if (summary) summary.textContent = describe(draft.rules[site]);
  }

  for (const site of SUPPORTED_SITES) {
    const rule = draft.rules[site.id];
    const section = element('section');
    section.dataset['siteRule'] = site.id;
    section.append(element('h2', site.label));

    // Order matters: the enabled box is the section's first checkbox.
    const enabled = element('input');
    enabled.type = 'checkbox';
    enabled.checked = rule.enabled;
    enabled.addEventListener('change', () => {
      rule.enabled = enabled.checked;
      refresh(site.id);
    });
    section.append(labelled('Enable this rule', enabled));

    for (const spec of NUMBER_FIELDS) {
      const input = element('input');
      input.type = 'number';
      input.min = String(spec.min);
      input.max = String(spec.max);
      input.value = String(rule[spec.field as NumberField]);
      input.dataset['field'] = spec.field;
      input.addEventListener('input', () => {
        rule[spec.field as NumberField] = Number(input.value);
        refresh(site.id);
      });
      section.append(labelled(spec.label, input));
    }

    const fieldset = element('fieldset');
    fieldset.append(element('legend', 'Interventions, in order'));
    for (const kind of INTERVENTION_ORDER) {
      const box = element('input');
      box.type = 'checkbox';
      box.checked = rule.interventions.includes(kind);
      box.addEventListener('change', () => {
        rule.interventions = INTERVENTION_ORDER.filter((candidate) =>
          candidate === kind ? box.checked : rule.interventions.includes(candidate),
        );
        refresh(site.id);
      });
      fieldset.append(labelled(INTERVENTION_LABELS[kind], box));
    }
    section.append(fieldset);

    section.append(element('p', 'Block duration: Until tomorrow'));

    const summary = element('p', describe(rule));
    summary.className = 'summary';
    summaries.set(site.id, summary);
    section.append(summary);

    const error = element('p');
    error.className = 'error';
    error.hidden = true;
    errors.set(site.id, error);
    section.append(error);

    form.append(section);
  }

  function validate(): boolean {
    let valid = true;
    for (const site of SUPPORTED_SITES) {
      const rule = draft.rules[site.id];
      const problems: string[] = [];

      for (const spec of NUMBER_FIELDS) {
        const value = rule[spec.field as NumberField];
        if (!Number.isInteger(value) || value < spec.min || value > spec.max) {
          problems.push(`${spec.label} must be a whole number from ${spec.min} to ${spec.max}.`);
        }
      }
      if (rule.enabled && rule.interventions.length === 0) {
        problems.push('An enabled rule needs at least one intervention.');
      }

      const slot = errors.get(site.id);
      if (slot) {
        slot.textContent = problems.join(' ');
        slot.hidden = problems.length === 0;
      }
      if (problems.length > 0) valid = false;
    }
    return valid;
  }

  const save = element('button', 'Save settings');
  save.type = 'submit';
  save.addEventListener('click', (event) => {
    event.preventDefault();
    // Invalid input keeps the persisted settings exactly as they are.
    if (!validate()) return;
    void request({ type: 'save-settings', settings: draft });
  });
  form.append(save);

  const review = element('section');
  review.append(element('h2', 'Recent interventions'));
  const list = element('ul');
  review.append(list);
  form.append(review);

  root.append(form);

  const history = await request({ type: 'get-interventions' });
  const records: InterventionRecord[] =
    history && history.ok && history.type === 'interventions' ? history.records : [];

  for (const item of [...records].reverse()) {
    const entry = element('li');
    const when = new Date(item.at).toLocaleString();
    entry.append(element('span', `${when} — ${item.site} — ${item.kind} — ${item.reason}`));
    for (const verdict of ['accurate', 'inaccurate'] as const) {
      const button = element('button', verdict === 'accurate' ? 'Accurate' : 'Inaccurate');
      button.type = 'button';
      button.addEventListener('click', () => {
        // Only the record id and the classification are ever sent.
        void request({ type: 'set-feedback', id: item.id, feedback: verdict });
      });
      entry.append(button);
    }
    list.append(entry);
  }
}

const mount = document.querySelector('#app');
if (mount) void renderOptions(mount);
