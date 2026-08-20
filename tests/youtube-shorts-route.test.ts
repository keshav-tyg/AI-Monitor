import { nextYouTubeShort, youtubeShortId } from '../src/content/youtube-shorts-route';

it('uses the first valid Short as the initial item, not an advance', () => {
  expect(nextYouTubeShort(undefined, 'https://www.youtube.com/shorts/first')).toEqual({
    id: 'first',
    advanced: false,
  });
});

it('emits exactly one advance when the Short identifier changes', () => {
  expect(nextYouTubeShort('first', 'https://www.youtube.com/shorts/second')).toEqual({
    id: 'second',
    advanced: true,
  });
  expect(nextYouTubeShort('second', 'https://www.youtube.com/shorts/second')).toEqual({
    id: 'second',
    advanced: false,
  });
});

it('fails open for non-Shorts and malformed URLs', () => {
  expect(youtubeShortId('https://www.youtube.com/watch?v=first')).toBeUndefined();
  expect(youtubeShortId('not a URL')).toBeUndefined();
  expect(youtubeShortId('https://www.youtube.com/shorts//first')).toBeUndefined();
});
