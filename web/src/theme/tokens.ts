/** Numbers the code needs. Colours and sizes live in tokens.css. */
export const motion = {
  /** one orchestrated reveal on load: rows arrive in order, briefly */
  rowStagger: 0.025,
  rowRise: 6,
  enter: { duration: 0.22, ease: [0.2, 0.8, 0.2, 1] as const },
  swap: { duration: 0.16, ease: [0.4, 0, 0.2, 1] as const },
};

export const timing = {
  /** how often the console asks the core for news; the core is the source of truth */
  pollMs: 3000,
  /** a window closing within this is shown as running out */
  fuseWarnMs: 2 * 60 * 60 * 1000,
};

export const windowLength = {
  WHATSAPP: 24 * 60 * 60 * 1000,
  INSTAGRAM: 7 * 24 * 60 * 60 * 1000, // people may answer for 7 days; the AI only for 24 hours
  SMS: Infinity,
  EMAIL: Infinity,
} as const;
