// Bulk manager actions on the rows of a session: select hundreds of rows with the standard
// filters (item group, brand, batch, location, status, counter) and act on them in one go.
frappe.listview_settings["Stocktaking Item"] = {
	add_fields: ["status", "counter", "qty_difference"],
	get_indicator(doc) {
		const colors = {
			"Not Counted": "gray",
			Assigned: "light-blue",
			Counting: "blue",
			Counted: "green",
			"Recount Required": "red",
			Recounted: "orange",
			"Manager Review": "orange",
			Approved: "green",
			Finalized: "green",
		};
		return [__(doc.status), colors[doc.status] || "gray", "status,=," + doc.status];
	},
	onload(listview) {
		const selected = () => listview.get_checked_items().map((d) => d.name);
		const method = "wmserp_picking.api.stocktaking.";
		listview.page.add_action_item(__("Assign to Counter"), () => {
			const names = selected();
			if (!names.length) return frappe.msgprint(__("Select the rows to assign first."));
			frappe.prompt(
				[
					{ fieldname: "user", fieldtype: "Link", options: "User", label: __("Counter"), reqd: 1 },
					{ fieldname: "area", fieldtype: "Data", label: __("Area") },
				],
				(values) => {
					const session = listview.get_checked_items()[0].session;
					frappe.call({ method: method + "assign_items", args: { name: session, user: values.user, items: names, area: values.area }, freeze: true }).then((r) => {
						frappe.show_alert({ message: __("{0} item(s) assigned to {1}", [r.message.assigned, values.user]), indicator: "green" });
						listview.refresh();
					});
				},
				__("Assign {0} selected item(s)", [names.length]),
				__("Assign")
			);
		});
		listview.page.add_action_item(__("Request Recount"), () => {
			const names = selected();
			if (!names.length) return frappe.msgprint(__("Select the rows to recount first."));
			frappe.prompt(
				{ fieldname: "note", fieldtype: "Small Text", label: __("Note for the counter") },
				(values) => {
					frappe.call({ method: method + "request_recounts", args: { items: names, note: values.note }, freeze: true }).then(() => {
						frappe.show_alert({ message: __("Recount requested for {0} item(s)", [names.length]), indicator: "orange" });
						listview.refresh();
					});
				},
				__("Request a recount of {0} item(s)", [names.length]),
				__("Request")
			);
		});
		listview.page.add_action_item(__("Accept Counts"), () => {
			const names = selected();
			if (!names.length) return frappe.msgprint(__("Select the rows to accept first."));
			frappe.confirm(__("Accept the latest count of {0} selected item(s) as final?", [names.length]), () => {
				frappe.call({ method: method + "accept_items", args: { items: names }, freeze: true }).then(() => {
					frappe.show_alert({ message: __("{0} item(s) accepted", [names.length]), indicator: "green" });
					listview.refresh();
				});
			});
		});
	},
};
