import { access, realpath } from "node:fs/promises";
import { constants as fsConstants } from "node:fs";
import { dirname, isAbsolute, relative, resolve } from "node:path";

export interface PathPolicy {
  expandHome(value: string): string;
  existingRoots(): Promise<string[]>;
  allowedPath(input: string, mustExist: boolean): Promise<string>;
}

function isInside(candidate: string, root: string): boolean {
  const rel = relative(root, candidate);
  return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
}

export function createPathPolicy(
  roots: string[],
  home: string
): PathPolicy {
  const expandHome = (value: string): string => {
    if (value === "~") return home;
    if (value.startsWith("~/")) return resolve(home, value.slice(2));
    return value;
  };

  const requestedRoots = roots.map(value => resolve(expandHome(value)));

  const existingRoots = async (): Promise<string[]> => {
    const result: string[] = [];

    for (const root of requestedRoots) {
      try {
        result.push(await realpath(root));
      } catch {
        // Android shared storage may not be granted yet.
      }
    }

    return result;
  };

  const nearestExistingParent = async (candidate: string): Promise<string> => {
    let current = candidate;

    while (true) {
      try {
        await access(current, fsConstants.F_OK);
        return current;
      } catch {
        const parent = dirname(current);
        if (parent === current) {
          throw new Error(`Не найден существующий родитель для ${candidate}`);
        }
        current = parent;
      }
    }
  };

  const allowedPath = async (
    input: string,
    mustExist: boolean
  ): Promise<string> => {
    const candidate = resolve(expandHome(input));
    const realRoots = await existingRoots();

    if (realRoots.length === 0) {
      throw new Error("Нет доступных разрешённых корневых каталогов");
    }

    let resolvedCandidate: string;

    if (mustExist) {
      resolvedCandidate = await realpath(candidate);
    } else {
      const existingParent = await nearestExistingParent(dirname(candidate));
      const realParent = await realpath(existingParent);
      const remainder = relative(existingParent, candidate);
      resolvedCandidate = resolve(realParent, remainder);
    }

    if (!realRoots.some(root => isInside(resolvedCandidate, root))) {
      throw new Error(
        `Доступ запрещён: ${input}. Разрешённые корни: ${realRoots.join(", ")}`
      );
    }

    return resolvedCandidate;
  };

  return {
    expandHome,
    existingRoots,
    allowedPath
  };
}
