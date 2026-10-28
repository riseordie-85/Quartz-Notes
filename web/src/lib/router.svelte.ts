// Screens are addressed by the part of the address after "#", so links and the back button work.

export type Route =
  | { name: "notes" }
  | { name: "note"; id: string }
  | { name: "archive" }
  | { name: "notebook"; id: string; page?: string }
  | { name: "search"; q: string }

function parse(hash: string): Route {
  const [path, query] = hash.replace(/^#\/?/, "").split("?")
  const parts = path.split("/").filter(Boolean).map(decodeURIComponent)
  switch (parts[0]) {
    case "note":
      return parts[1] ? { name: "note", id: parts[1] } : { name: "notes" }
    case "archive":
      return { name: "archive" }
    case "notebook":
      return parts[1] ? { name: "notebook", id: parts[1], page: parts[2] } : { name: "notes" }
    case "search":
      return { name: "search", q: new URLSearchParams(query ?? "").get("q") ?? "" }
    default:
      return { name: "notes" }
  }
}

export function href(route: Route): string {
  switch (route.name) {
    case "notes":
      return "#/"
    case "note":
      return `#/note/${encodeURIComponent(route.id)}`
    case "archive":
      return "#/archive"
    case "notebook":
      return `#/notebook/${encodeURIComponent(route.id)}${route.page ? `/${encodeURIComponent(route.page)}` : ""}`
    case "search":
      return `#/search?q=${encodeURIComponent(route.q)}`
  }
}

class Router {
  route: Route = $state(parse(location.hash))

  constructor() {
    addEventListener("hashchange", () => (this.route = parse(location.hash)))
  }

  go(route: Route, replace = false) {
    const target = href(route)
    if (replace) history.replaceState(null, "", target)
    else if (location.hash !== target) history.pushState(null, "", target)
    this.route = parse(target)
  }
}

export const router = new Router()
