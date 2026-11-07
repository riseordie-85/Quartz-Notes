// Which side panels are tucked away on wide screens (remembered in this browser): the app's
// sidebar, and a notebook's page list.

const KEY = "quartz-panels"

function saved(): { sidebar: boolean; pages: boolean } {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) ?? "{}")
    return { sidebar: v.sidebar === true, pages: v.pages === true }
  } catch {
    return { sidebar: false, pages: false }
  }
}

const narrowQuery = typeof matchMedia !== "undefined" ? matchMedia("(max-width: 860px)") : null

class Panels {
  /** Narrow screens show the sidebar as a drawer instead. */
  narrow = $state(narrowQuery?.matches ?? false)

  /** The sidebar (notebooks, quick notes, search) is hidden. */
  sidebarHidden = $state(saved().sidebar)
  /** A notebook's page list is hidden. */
  pagesHidden = $state(saved().pages)

  constructor() {
    narrowQuery?.addEventListener("change", (e) => (this.narrow = e.matches))
  }

  private save() {
    try {
      localStorage.setItem(KEY, JSON.stringify({ sidebar: this.sidebarHidden, pages: this.pagesHidden }))
    } catch {
      // Storage blocked: it just won't be remembered.
    }
  }

  toggleSidebar(hidden = !this.sidebarHidden) {
    this.sidebarHidden = hidden
    this.save()
  }

  togglePages(hidden = !this.pagesHidden) {
    this.pagesHidden = hidden
    this.save()
  }
}

export const panels = new Panels()
