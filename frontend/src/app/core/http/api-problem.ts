import { HttpErrorResponse } from '@angular/common/http';

/** An API error as the pages handle it: the stable code of the RFC 9457 problem, and a message in French. */
export class ApiProblem extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly errors: Record<string, string> = {},
  ) {
    super(message);
    this.name = 'ApiProblem';
  }

  static from(response: HttpErrorResponse): ApiProblem {
    const body: unknown = response.error;
    const problem =
      typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
    const code =
      typeof problem['code'] === 'string' ? problem['code'] : fallbackCode(response.status);
    const errors = isStringRecord(problem['errors']) ? problem['errors'] : {};
    return new ApiProblem(response.status, code, messageOf(code), errors);
  }
}

/** Messages for the users of the application: what happened and, when there is one, what to do. */
const MESSAGES: Record<string, string> = {
  UNAUTHENTICATED: 'Ta session a expiré. Connecte-toi à nouveau.',
  ACCESS_DENIED: "Tu n'as pas les droits pour cette action.",
  NOT_EVENT_ORGANIZER: "Seul l'organisateur de cet événement peut faire cette action.",
  USER_NOT_FOUND: 'Profil introuvable.',
  EVENT_NOT_FOUND: 'Cet événement est introuvable.',
  REGISTRATION_NOT_FOUND: 'Cette inscription est introuvable.',
  ALREADY_REGISTERED: 'Tu es déjà inscrit à cet événement.',
  EVENT_FULL: "L'événement est complet.",
  EVENT_NOT_REGISTRABLE: 'Les inscriptions ne sont pas ouvertes pour cet événement.',
  REGISTRATION_ALREADY_CANCELLED: 'Cette inscription est déjà annulée.',
  EMAIL_ALREADY_USED: 'Cette adresse e-mail est déjà utilisée par un autre compte.',
  INVALID_EVENT_STATE: "Cette action n'est pas possible dans l'état actuel de l'événement.",
  EVENT_HAS_RESERVATIONS: "L'événement a des inscrits : annule-le plutôt que de le supprimer.",
  CAPACITY_BELOW_RESERVED:
    'La capacité ne peut pas être inférieure au nombre de places déjà réservées.',
  CONCURRENT_MODIFICATION: 'Les données ont changé entre-temps. Recharge la page et réessaie.',
  VALIDATION_FAILED: 'Certains champs sont invalides.',
  INVALID_SORT_PROPERTY: 'Tri non pris en charge.',
  EVENT_START_NOT_IN_FUTURE: 'La date de début doit être dans le futur.',
  EMAIL_CLAIM_MISSING: "Ton compte n'a pas d'adresse e-mail : complète-le dans Keycloak.",
  DOWNSTREAM_UNAVAILABLE: 'Le service est momentanément indisponible. Réessaie dans un instant.',
  SERVICE_UNAVAILABLE: 'Le service est momentanément indisponible. Réessaie dans un instant.',
  UPSTREAM_TIMEOUT: 'Le service met trop de temps à répondre. Réessaie dans un instant.',
  NETWORK_ERROR: 'Connexion impossible. Vérifie ta connexion internet.',
};

const UNEXPECTED = "Une erreur inattendue s'est produite. Réessaie plus tard.";

export function messageOf(code: string): string {
  return MESSAGES[code] ?? UNEXPECTED;
}

/** No problem body (network failure, proxy error page): a code from the status alone. */
function fallbackCode(status: number): string {
  switch (status) {
    case 0:
      return 'NETWORK_ERROR';
    case 401:
      return 'UNAUTHENTICATED';
    case 403:
      return 'ACCESS_DENIED';
    case 502:
    case 503:
      return 'SERVICE_UNAVAILABLE';
    case 504:
      return 'UPSTREAM_TIMEOUT';
    default:
      return 'INTERNAL_ERROR';
  }
}

function isStringRecord(value: unknown): value is Record<string, string> {
  return (
    typeof value === 'object' &&
    value !== null &&
    Object.values(value).every((entry) => typeof entry === 'string')
  );
}
