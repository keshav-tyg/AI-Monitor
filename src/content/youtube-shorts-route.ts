export function youtubeShortId(href: string): string | undefined {
  let url: URL;
  try {
    url = new URL(href);
  } catch {
    return undefined;
  }

  if (url.hostname.replace(/^www\./, '') !== 'youtube.com') return undefined;

  const [, route, id] = url.pathname.split('/');
  return route === 'shorts' && id ? id : undefined;
}

export function nextYouTubeShort(
  previousId: string | undefined,
  href: string,
): { id: string; advanced: boolean } | undefined {
  const id = youtubeShortId(href);
  if (!id) return undefined;

  return { id, advanced: previousId !== undefined && previousId !== id };
}
