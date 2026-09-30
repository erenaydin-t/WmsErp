// Manager side of the stocktaking workflow:
// Create -> Start (freeze + snapshot) -> Assign -> Monitor -> Close counting -> Review variances
// -> Request recounts -> Approve -> Create Stock Reconciliation -> Complete (unfreeze).
const STOCKTAKING_API = "wmserp_picking.api.stocktaking.";

frappe.ui.form.on("Stocktaking Session", {
	refresh(frm) {
		frm.page.clear_actions_menu();
		render_progress(frm);
		add_links(frm);
		add_buttons(frm);
		set_intro(frm);
	},

	warehouse(frm) {
		if (frm.doc.warehouse && !frm.doc.company) {
			frappe.db.get_value("Warehouse", frm.doc.warehouse, "company").then((r) => {
				if (r.message && r.message.company) frm.set_value("company", r.message.company);
			});
		}
	},
});

function call(frm, method, args, { freeze = true, message = null } = {}) {
	return frappe
		.call({
			method: STOCKTAKING_API + method,
			args: Object.assign({ name: frm.doc.name }, args || {}),
			freeze,
			freeze_message: message || __("Working..."),
		})
		.then((r) => {
			frm.reload_doc();
			return r.message;
		});
}

function set_intro(frm) {
	frm.set_intro("");
	if (frm.is_new()) {
		frm.set_intro(__("Choose the warehouse and the counting mode, add the counters, then Start. Starting snapshots the ERP quantities of every item / batch and freezes the warehouse."), "blue");
	} else if (frm.doc.status === "Counting") {
		frm.set_intro(__("Counters are scanning. Close counting once every item is counted; the report Stocktaking Uncounted Items shows what is left."), "blue");
	} else if (frm.doc.status === "Manager Review") {
		frm.set_intro(__("Review the differences (Stocktaking Variance report or the Stocktaking Item list): accept them or request recounts, then Approve."), "orange");
	} else if (frm.doc.status === "Recount") {
		frm.set_intro(__("Recounts are pending. The session returns to Manager Review when the last one is in."), "orange");
	} else if (frm.doc.status === "Final Approval") {
		frm.set_intro(__("Approved. Create the Stock Reconciliation from the final quantities."), "green");
	} else if (frm.doc.status === "Reconciled") {
		frm.set_intro(__("Submit the Stock Reconciliation to post the differences; the session completes and the warehouse is unfrozen automatically."), "green");
	} else if (frm.doc.frozen) {
		frm.set_intro(__("Warehouse {0} is frozen: stock transactions are blocked until the session is completed or cancelled.", [frm.doc.warehouse]), "red");
	}
}

function add_links(frm) {
	if (frm.is_new()) return;
	frm.add_custom_button(__("Items"), () => frappe.set_route("List", "Stocktaking Item", { session: frm.doc.name }), __("View"));
	frm.add_custom_button(__("Uncounted Items"), () => frappe.set_route("query-report", "Stocktaking Uncounted Items", { session: frm.doc.name }), __("View"));
	frm.add_custom_button(__("Variances"), () => frappe.set_route("query-report", "Stocktaking Variance", { session: frm.doc.name }), __("View"));
	frm.add_custom_button(__("Count History"), () => frappe.set_route("List", "Stocktaking Count", { session: frm.doc.name }), __("View"));
	if (frm.doc.stock_reconciliation) {
		frm.add_custom_button(__("Stock Reconciliation"), () => frappe.set_route("Form", "Stock Reconciliation", frm.doc.stock_reconciliation), __("View"));
	}
}

function add_buttons(frm) {
	if (frm.is_new() || !frm.perm[0].write) return;
	const status = frm.doc.status;
	const primary = (label, fn) => frm.add_custom_button(label, fn).addClass("btn-primary");

	if (status === "Draft") {
		primary(__("Start Stocktaking"), () => {
			frappe.confirm(
				__("Snapshot the ERP quantities of {0} and {1}?", [frm.doc.warehouse, frm.doc.freeze_warehouse ? __("freeze it for stock transactions") : __("start counting without freezing")]),
				() => call(frm, "start_session", {}, { message: __("Snapshotting stock...") })
			);
		});
	}
	if (["Draft", "Counting", "Recount", "Manager Review"].includes(status)) {
		frm.add_custom_button(__("Assign Items"), () => assign_dialog(frm), __("Actions"));
		frm.add_custom_button(__("Unassign Items"), () => assign_dialog(frm, true), __("Actions"));
	}
	if (["Counting", "Recount"].includes(status)) {
		primary(__("Close Counting"), () => {
			frappe.confirm(__("Close counting? Every item must be counted and every requested recount done."), () => call(frm, "complete_counting"));
		});
	}
	if (["Manager Review", "Recount", "Final Approval", "Counting"].includes(status)) {
		frm.add_custom_button(__("Accept All Pending Variances"), () => {
			frappe.prompt(
				{ fieldname: "note", fieldtype: "Small Text", label: __("Review note (optional)") },
				(values) => call(frm, "accept_all", { note: values.note }),
				__("Accept every row waiting for manager review"),
				__("Accept")
			);
		}, __("Actions"));
	}
	if (["Manager Review", "Recount"].includes(status)) {
		primary(__("Approve"), () => frappe.confirm(__("Give final approval to the counted quantities?"), () => call(frm, "approve_session")));
	}
	if (status === "Final Approval") {
		primary(__("Create Stock Reconciliation"), () => {
			frappe.confirm(
				__("Create a draft Stock Reconciliation with every approved difference? You submit it afterwards to post the stock changes."),
				() => call(frm, "create_reconciliation", { submit: 0 }, { message: __("Creating Stock Reconciliation...") }).then((r) => {
					if (r && r.stock_reconciliation) frappe.set_route("Form", "Stock Reconciliation", r.stock_reconciliation);
				})
			);
		});
	}
	if (status === "Reconciled") {
		primary(__("Complete"), () => call(frm, "complete_session"));
	}
	if (!["Completed", "Cancelled"].includes(status)) {
		frm.add_custom_button(__("Cancel Stocktaking"), () => {
			frappe.prompt(
				{ fieldname: "reason", fieldtype: "Small Text", label: __("Reason") },
				(values) => call(frm, "cancel_session", { reason: values.reason }),
				__("Cancel this stocktaking session and unfreeze the warehouse?"),
				__("Cancel Stocktaking")
			);
		}, __("Actions"));
	}
}

function assign_dialog(frm, unassign = false) {
	const fields = [
		{ fieldname: "counter", fieldtype: "Link", options: "User", label: unassign ? __("Currently assigned to (empty = any)") : __("Assign to"), reqd: unassign ? 0 : 1 },
		{ fieldname: "area", fieldtype: "Data", label: __("Area (shown on the counter row)"), depends_on: unassign ? "eval:false" : "" },
		{ fieldtype: "Section Break", label: __("Which items") },
		{ fieldname: "item_group", fieldtype: "Link", options: "Item Group", label: __("Item Group (incl. sub-groups)") },
		{ fieldname: "brand", fieldtype: "Link", options: "Brand", label: __("Brand") },
		{ fieldname: "batch_no", fieldtype: "Link", options: "Batch", label: __("Batch") },
		{ fieldtype: "Column Break" },
		{ fieldname: "warehouse", fieldtype: "Link", options: "Warehouse", label: __("Warehouse") },
		{ fieldname: "location", fieldtype: "Data", label: __("Location contains") },
		{ fieldname: "item_name", fieldtype: "Data", label: __("Item name contains") },
		{ fieldname: "only_unassigned", fieldtype: "Check", label: __("Only rows without a counter"), default: unassign ? 0 : 1 },
		{ fieldtype: "Section Break" },
		{ fieldname: "preview", fieldtype: "HTML" },
	];
	const dialog = new frappe.ui.Dialog({
		title: unassign ? __("Unassign Items") : __("Assign Items to a Counter"),
		fields,
		primary_action_label: unassign ? __("Unassign") : __("Assign"),
		primary_action(values) {
			const filters = build_filters(values, unassign);
			frappe.call({
				method: STOCKTAKING_API + "assign_items",
				args: { name: frm.doc.name, user: unassign ? null : values.counter, filters, unassign: unassign ? 1 : 0, area: values.area },
				freeze: true,
			}).then((r) => {
				dialog.hide();
				frappe.show_alert({ message: __("{0} item(s) {1}", [r.message.assigned, unassign ? __("unassigned") : __("assigned to {0}", [r.message.user])]), indicator: "green" });
				frm.reload_doc();
			});
		},
	});
	const preview = () => {
		const values = dialog.get_values(true) || {};
		frappe.call({ method: STOCKTAKING_API + "count_assignable", args: { name: frm.doc.name, filters: build_filters(values, unassign) } }).then((r) => {
			dialog.fields_dict.preview.$wrapper.html(`<p class="text-muted">${__("{0} item(s) match these filters and can still be assigned.", [r.message])}</p>`);
		});
	};
	["item_group", "brand", "batch_no", "warehouse", "location", "item_name", "only_unassigned", "counter"].forEach((f) => {
		if (dialog.fields_dict[f]) dialog.fields_dict[f].df.onchange = preview;
	});
	dialog.show();
	preview();
}

function build_filters(values, unassign) {
	const filters = {};
	["item_group", "brand", "batch_no", "warehouse", "location", "item_name"].forEach((key) => {
		if (values[key]) filters[key] = values[key];
	});
	if (unassign) {
		if (values.counter) filters.counter = values.counter;
	} else if (values.only_unassigned) {
		filters.counter = "";
	}
	return filters;
}

function render_progress(frm) {
	if (frm.is_new()) return;
	const d = frm.doc;
	const total = d.total_items || 0;
	const pct = (n) => (total ? Math.round((n / total) * 100) : 0);
	const bar = (label, value, color) =>
		`<div style="margin-bottom:6px"><div class="text-muted small">${label}: <b>${value}</b> (${pct(value)}%)</div>
		 <div class="progress" style="height:8px;margin:0"><div class="progress-bar" role="progressbar" style="width:${pct(value)}%;background:${color}"></div></div></div>`;
	const html = `
		<div class="row">
			<div class="col-sm-6">
				${bar(__("Counted"), d.counted_items || 0, "var(--green-500)")}
				${bar(__("Not counted"), d.uncounted_items || 0, "var(--gray-500)")}
				${bar(__("Matched"), d.matched_items || 0, "var(--blue-500)")}
			</div>
			<div class="col-sm-6">
				${bar(__("Variance"), d.variance_items || 0, "var(--orange-500)")}
				${bar(__("Recount required"), d.recount_required || 0, "var(--red-500)")}
				${bar(__("Pending manager review"), d.pending_review || 0, "var(--yellow-500)")}
			</div>
		</div>
		<div class="text-muted small">${__("Quantity variance")}: <b>${format_number(d.qty_variance || 0)}</b> &nbsp;·&nbsp; ${__("Value variance")}: <b>${format_currency(d.value_variance || 0)}</b></div>`;
	frm.dashboard.add_section(html, __("Stocktaking Progress"));
}
