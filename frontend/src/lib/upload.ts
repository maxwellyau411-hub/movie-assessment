/**
 * Poster upload as a graphql-multipart-request-spec request on /graphql (D11). XMLHttpRequest is
 * used instead of fetch because only XHR reports upload progress (`upload.onprogress`), which
 * gives a real byte progress bar.
 */
import { GraphQLRequestError, parseGraphQLResponse } from './graphql';
import { listCache } from './listCache';
import type { Artwork } from './types';

export const MAX_BYTES = 5 * 1024 * 1024;
export const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

export interface UploadedPoster {
  id: string;
  version: number;
  artwork: Artwork;
}

const MUTATION = `mutation UploadPoster($movieId: ID!, $file: Upload!) {
  uploadMoviePoster(movieId: $movieId, file: $file) { id version artwork { thumbnailUrl fullUrl width height } }
}`;

/** Quick checks before sending, for a faster message. The server checks the actual bytes anyway. */
export function precheck(file: { size: number; type: string }): string | null {
  if (!ACCEPTED_TYPES.includes(file.type)) return 'Choose a JPEG, PNG or WebP image.';
  if (file.size === 0) return 'The file is empty.';
  if (file.size > MAX_BYTES) return 'The file is larger than 5 MB.';
  return null;
}

/** `operations` holds the query with the file variable set to null; `map` says part "0" fills it. */
export function buildUploadForm(movieId: string, file: Blob, fileName: string): FormData {
  const form = new FormData();
  form.append('operations', JSON.stringify({ query: MUTATION, variables: { movieId, file: null } }));
  form.append('map', JSON.stringify({ 0: ['variables.file'] }));
  form.append('0', file, fileName);
  return form;
}

export interface UploadCallbacks {
  /** Bytes sent so far and the total, while the file is on its way. */
  onProgress?: (loaded: number, total: number) => void;
  /** All bytes sent; the server is now checking and storing the image. */
  onProcessing?: () => void;
  signal?: AbortSignal;
}

export function uploadPoster(
  movieId: string,
  file: File,
  { onProgress, onProcessing, signal }: UploadCallbacks = {},
  createXhr: () => XMLHttpRequest = () => new XMLHttpRequest(),
): Promise<UploadedPoster> {
  return new Promise((resolve, reject) => {
    const xhr = createXhr();
    xhr.open('POST', '/graphql');
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress?.(event.loaded, event.total);
    };
    xhr.upload.onload = () => onProcessing?.();
    xhr.onload = () => {
      if (xhr.status === 413) {
        // A refused file, like the server's own size check: a field error on `file`.
        const message = 'The file is larger than 5 MB.';
        reject(
          new GraphQLRequestError(message, 'VALIDATION_ERROR', 413, {
            fieldErrors: [{ field: 'file', message }],
          }),
        );
        return;
      }
      try {
        const data = parseGraphQLResponse<{ uploadMoviePoster: UploadedPoster }>(
          xhr.status,
          xhr.responseText,
        );
        listCache.clear();
        resolve(data.uploadMoviePoster);
      } catch (error) {
        reject(error);
      }
    };
    xhr.onerror = () =>
      reject(new GraphQLRequestError('Network error: the upload did not finish', 'NETWORK_ERROR', 0));
    xhr.onabort = () => reject(new GraphQLRequestError('Upload cancelled', 'ABORTED', 0));
    signal?.addEventListener('abort', () => xhr.abort(), { once: true });
    xhr.send(buildUploadForm(movieId, file, file.name));
  });
}
