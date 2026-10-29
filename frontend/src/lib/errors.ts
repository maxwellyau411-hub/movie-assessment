import { GraphQLRequestError } from './graphql';

/** True when someone else saved a newer version first: the user must reload before trying again. */
export function isStale(error: unknown): boolean {
  return error instanceof GraphQLRequestError && error.reason === 'STALE_VERSION';
}

/**
 * One sentence for the user. Conflict messages from the server already name the person or movie
 * involved, so they are shown as they are; the rest get a fixed wording.
 */
export function describeError(error: unknown): string {
  if (!(error instanceof GraphQLRequestError)) return 'Something went wrong. Please try again.';
  switch (error.code) {
    case 'VALIDATION_ERROR':
      return Object.keys(error.fieldErrors).length
        ? 'Please correct the highlighted fields.'
        : error.message.endsWith('.')
          ? error.message
          : `${error.message}.`;
    case 'NOT_FOUND':
      return 'This record no longer exists. It may have been deleted.';
    case 'CONFLICT':
      return isStale(error)
        ? 'Someone else changed this since you opened it. Reload to see the latest version, then make your change again.'
        : error.message;
    case 'SERVICE_UNAVAILABLE':
      return `${error.message}.`;
    case 'NETWORK_ERROR':
      return 'Could not reach the server. Check your connection and try again.';
    default:
      return 'Something went wrong on the server. Please try again.';
  }
}
