import iso19139.SummaryFactory

def isoHandlers = new iso19139.Handlers(handlers, f, env) {
    {
        def oldImpl = super.isoTextEl
        isoTextEl = { el ->
            "----------- ${oldImpl(el)}"
        }
    }
}

def factory = new SummaryFactory(isoHandlers, {summary ->
    summary.title = "My Title"
    summary.addCompleteNavItem = false
    summary.addOverviewNavItem = false
    summary.associated.clear()
})


handlers.add name: "Summary Handler",
        select: {it.parent() is it.parent()},
        {factory.create(it).getResult()}
isoHandlers.addDefaultHandlers()