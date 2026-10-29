import { describe, expect, it } from 'vitest';
import { buildUploadForm, precheck, uploadPoster } from './upload';

describe('precheck', () => {
  it('accepts JPEG, PNG and WebP up to 5 MB and explains anything else', () => {
    expect(precheck({ type: 'image/png', size: 5 * 1024 * 1024 })).toBeNull();
    expect(precheck({ type: 'image/webp', size: 10 })).toBeNull();
    expect(precheck({ type: 'image/gif', size: 10 })).toMatch(/JPEG, PNG or WebP/);
    expect(precheck({ type: 'image/jpeg', size: 0 })).toMatch(/empty/);
    expect(precheck({ type: 'image/jpeg', size: 5 * 1024 * 1024 + 1 })).toMatch(/5 MB/);
  });
});

describe('buildUploadForm', () => {
  it('follows the GraphQL multipart request spec: operations, map, then file part 0', async () => {
    const form = buildUploadForm('movie-1', new Blob(['png'], { type: 'image/png' }), 'poster.png');

    expect([...form.keys()]).toEqual(['operations', 'map', '0']);
    const operations = JSON.parse(String(form.get('operations')));
    expect(operations.variables).toEqual({ movieId: 'movie-1', file: null });
    expect(operations.query).toContain('uploadMoviePoster(movieId: $movieId, file: $file)');
    expect(JSON.parse(String(form.get('map')))).toEqual({ 0: ['variables.file'] });
    const file = form.get('0') as File;
    expect(file.name).toBe('poster.png');
    expect(await file.text()).toBe('png');
  });
});

/** Just enough of XMLHttpRequest for uploadPoster; the test plays the browser's events. */
class FakeXhr {
  status = 0;
  responseText = '';
  upload: { onprogress?: (e: ProgressEvent) => void; onload?: () => void } = {};
  onload?: () => void;
  onerror?: () => void;
  onabort?: () => void;
  sent: unknown;
  aborted = false;
  open() {}
  setRequestHeader() {}
  send(body: unknown) {
    this.sent = body;
  }
  abort() {
    this.aborted = true;
    this.onabort?.();
  }
  progress(loaded: number, total: number) {
    this.upload.onprogress?.({ lengthComputable: true, loaded, total } as ProgressEvent);
  }
  finish(status: number, body: unknown) {
    this.upload.onload?.();
    this.status = status;
    this.responseText = typeof body === 'string' ? body : JSON.stringify(body);
    this.onload?.();
  }
}

function start(signal?: AbortSignal) {
  const xhr = new FakeXhr();
  const events: string[] = [];
  const result = uploadPoster(
    'movie-1',
    new File(['x'.repeat(100)], 'poster.png', { type: 'image/png' }),
    { onProgress: (l, t) => events.push(`${l}/${t}`), onProcessing: () => events.push('processing'), signal },
    () => xhr as unknown as XMLHttpRequest,
  );
  return { xhr, events, result };
}

describe('uploadPoster', () => {
  it('reports byte progress, then processing, then resolves with the new artwork and version', async () => {
    const { xhr, events, result } = start();
    xhr.progress(40, 100);
    xhr.progress(100, 100);
    const poster = {
      id: 'movie-1',
      version: 3,
      artwork: { thumbnailUrl: '/t', fullUrl: '/f', width: 1, height: 1 },
    };
    xhr.finish(200, { data: { uploadMoviePoster: poster } });

    await expect(result).resolves.toEqual(poster);
    expect(events).toEqual(['40/100', '100/100', 'processing']);
  });

  it('turns a refused file into a VALIDATION_ERROR with the server message on field file', async () => {
    const { xhr, result } = start();
    xhr.finish(200, {
      errors: [
        {
          message: 'Some fields are invalid',
          extensions: {
            code: 'VALIDATION_ERROR',
            fieldErrors: [{ field: 'file', message: 'The file content is PNG' }],
          },
        },
      ],
    });

    await expect(result).rejects.toMatchObject({
      code: 'VALIDATION_ERROR',
      fieldErrors: { file: 'The file content is PNG' },
    });
  });

  it("reports nginx's HTML 413 page as a size error", async () => {
    const { xhr, result } = start();
    xhr.finish(413, '<html>413 Request Entity Too Large</html>');

    await expect(result).rejects.toMatchObject({
      code: 'VALIDATION_ERROR',
      message: 'The file is larger than 5 MB.',
    });
    await expect(result).rejects.toHaveProperty('fieldErrors', { file: 'The file is larger than 5 MB.' });
  });

  it('aborting the signal cancels the request', async () => {
    const controller = new AbortController();
    const { xhr, result } = start(controller.signal);

    controller.abort();

    expect(xhr.aborted).toBe(true);
    await expect(result).rejects.toMatchObject({ code: 'ABORTED' });
  });
});
