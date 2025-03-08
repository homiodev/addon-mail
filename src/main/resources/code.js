class MailWidget extends HTMLElement {
    searchQuery = '';
    mails = [];
    currentPage = 1;
    itemsPerPage = 10;
    sortBy = 'date';
    sortDir = 'desc';
    currentFilter = 'all';
    isComposeOpen = false;
    attachments = [];

    setContext(widget) {
        this.widget = widget;
        widget.dataWarehouse.subscribe(data => {
            this.mails = data || [];
            if(!this.viewingMail && !this.isComposeOpen) {
                this.render();
            }
        });
    }

    render() {
        if (this.isComposeOpen) {
            this.content.innerHTML = `
                <div class="compose-mail-form">
                    <h3>Compose Mail</h3>
                    <form id="composeForm" class="composeForm">
                        <input type="email" placeholder="To" id="toInput" required>
                        <input type="text" placeholder="Subject" id="subjectInput" required>
                        <div class="attachments">
                            <div class="attachment">
                                <input type="file" class="attachmentInput">
                                <button type="button" class="remove-button">❌</button>
                            </div>
                        </div>   
                        <textarea placeholder="Message" id="messageInput" required></textarea>
                        <button type="submit">Send</button>
                        <button type="button" class="cancel-button">Cancel</button>
                    </form>
                </div> 
            `;
            this.shadowRoot.querySelector('.cancel-button').addEventListener('click', () => this.closeComposeForm());
            this.shadowRoot.querySelector('#composeForm').addEventListener('submit', (e) => this.sendMail(e));
            const attachmentsDiv = this.shadowRoot.querySelector('.attachments');
            attachmentsDiv.addEventListener("change", function (event) {
                if (event.target.classList.contains("attachmentInput")) {
                    if (event.target.files.length > 0) {
                        const attachmentDiv = document.createElement("div");
                        attachmentDiv.classList.add("attachment");

                        const newInput = document.createElement("input");
                        newInput.type = "file";
                        newInput.classList.add("attachmentInput");

                        const removeButton = document.createElement("button");
                        removeButton.type = "button";
                        removeButton.classList.add("remove-button");
                        removeButton.classList.add("visible");
                        removeButton.textContent = "❌";
                        removeButton.addEventListener("click", function () {
                            attachmentDiv.remove();
                        });

                        attachmentDiv.appendChild(newInput);
                        attachmentDiv.appendChild(removeButton);
                        attachmentsDiv.appendChild(attachmentDiv);
                    }
                }
            });

            attachmentsDiv.addEventListener("click", function (event) {
                if (event.target.classList.contains("remove-button")) {
                    event.target.parentElement.remove();
                }
            });
            return;
        }
        if(this.viewingMail) {
            this.content.innerHTML = `
            <div class="table-container" style="height: ${this.widget.height}px">
            ${this.viewingMail.html || this.viewingMail.preview || this.viewingMail.description}
            </div>
            <button class="close-mail-button">X</button>`;
            this.shadowRoot.querySelector('.close-mail-button').addEventListener('click', (e) => {
                this.viewingMail = null;
                this.render();
            });
            return;
        }
        const filteredMails = this.getFilteredMails();
        const start = (this.currentPage - 1) * this.itemsPerPage;
        const end = start + this.itemsPerPage;
        const paginatedMails = filteredMails.slice(start, end);
        const totalPages = Math.ceil(filteredMails.length / this.itemsPerPage);
        const unReadMails = this.mails.filter(mail=>!mail.seen).length;
        const attachMails = this.mails.filter(mail=>mail.attachments?.length > 0).length;

        this.content.innerHTML = `
      <div class="mail-widget">
        <div class="filter-controls">
          <button class="filter-btn ${this.currentFilter === 'all' ? 'active' : ''}" 
                  data-filter="all">All(${this.mails.length})</button>
          <button class="filter-btn ${this.currentFilter === 'unread' ? 'active' : ''}" 
                  data-filter="unread">Unread(${unReadMails})</button>
          <button class="filter-btn ${this.currentFilter === 'attachments' ? 'active' : ''}" 
                  data-filter="attachments">Attach(${attachMails})</button>
                  <div class="search">
          <input value="${this.searchQuery}" class="search-input" placeholder="Search emails...">
          <div class="counter">${filteredMails.length}</div>
          </div>
            <button class="compose-button">Compose</button>
          <div class="pagination-controls">
  <button class="page-button prev-button" 
          ${this.currentPage === 1 ? 'disabled' : ''}>
    <
  </button>
  <span>Page ${this.currentPage} of ${totalPages}
            <select class="items-per-page-select">
            ${[5, 10, 15, 20, 50, 100, 1000].map(option =>
            `<option value="${option}" ${this.itemsPerPage === option ? 'selected' : ''}>${option}</option>`
        ).join('')}
            </select> 
           </span>
  <button class="page-button next-button" 
          ${this.currentPage === totalPages ? 'disabled' : ''}>
    >
  </button>
        </div>        
        </div>

        <div class="table-container" style="height: ${this.widget.height - 24}px">
        <table class="mail-table">
          <thead>
            <tr>
              <th class="sender-cell sort-header" data-sort="sender">
                Sender
                ${this.sortBy === 'sender' ?
            `<span class="sort-indicator">${this.sortDir === 'asc' ? '↑' : '↓'}</span>` : ''}
              </th>
              <th class="sort-header" data-sort="subject">
                Subject
                ${this.sortBy === 'subject' ?
            `<span class="sort-indicator">${this.sortDir === 'asc' ? '↑' : '↓'}</span>` : ''}
              </th>
              <th class="date-cell sort-header" data-sort="date">
                Date
                ${this.sortBy === 'date' ?
            `<span class="sort-indicator">${this.sortDir === 'asc' ? '↑' : '↓'}</span>` : ''}
              </th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            ${paginatedMails.map(mail => `
              <tr class="mail-row ${mail.seen ? '' : 'unread'}" data-id="${mail.id}">
                <td>
                  ${mail.attachments?.length > 0 ? '<span class="attachment-icon">📎</span>' : ''}
                  ${mail.sender}
                </td>
                <td>
                  <div class="subject-line">${mail.subject}</div>
                  ${mail.preview || mail.description ? `<div class="preview-text">${mail.preview || mail.description}</div>` : ''}
                </td>
                <td>
                  ${this.formatDate(mail.receivedDate)}
                </td>
                <td>
                  <button class="delete-button" data-id="${mail.id}">×</button>
                </td>
              </tr>
            `).join('')}
          </tbody>
        </table>
        </div>
      </div>
    `;

        const searchInput = this.shadowRoot.querySelector('.search-input');
        if (searchInput) {
            if(this.searchQuery) {
                searchInput.focus();
                searchInput.setSelectionRange(searchInput.value.length, searchInput.value.length);
            }
            searchInput.addEventListener('input', (e) => this.handleSearch(e));
        }

        this.shadowRoot.querySelector('.items-per-page-select').addEventListener('change', (e) => {
            this.itemsPerPage = parseInt(e.target.value, 10);
            this.render();
        });
        this.shadowRoot.querySelectorAll('.sort-header').forEach(header => {
            header.addEventListener('click', (e) => this.handleSort(e));
        });

        this.shadowRoot.querySelectorAll('.filter-btn').forEach(btn => {
            btn.addEventListener('click', (e) => this.handleFilter(e));
        });

        this.shadowRoot.querySelectorAll('.delete-button').forEach(btn => {
            btn.addEventListener('click', (e) => this.deleteMail(e));
        });

        this.shadowRoot.querySelectorAll('.mail-row').forEach(row => {
            row.addEventListener('click', (e) => this.viewMail(e));
        });

        this.shadowRoot.querySelector('.prev-button')?.addEventListener('click', () => this.prevPage());
        this.shadowRoot.querySelector('.next-button')?.addEventListener('click', () => this.nextPage());
        this.shadowRoot.querySelector('.compose-button').addEventListener('click', () => this.openComposeForm());
    }

    handleSort(event) {
        const sortField = event.target.closest('.sort-header').dataset.sort;
        if (this.sortBy === sortField) {
            this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc';
        } else {
            this.sortBy = sortField;
            this.sortDir = 'desc';
        }
        this.currentPage = 1;
        this.render();
    }

    sendMail(event) {
        event.preventDefault();
        const to = this.shadowRoot.querySelector('#toInput').value;
        const subject = this.shadowRoot.querySelector('#subjectInput').value;
        const body = this.shadowRoot.querySelector('#messageInput').value;
        const fileInput = this.shadowRoot.querySelector('#attachmentInput');
        const file = fileInput.files[0];

        const formData = new FormData();
        formData.append('to', to);
        formData.append('subject', subject);
        formData.append('body', body);
        if (file) {
            formData.append('attachment', file);
        }

        this.widget.callService('sendMail', formData).subscribe(response => {
            // this.closeComposeForm();
        });
    }

    openComposeForm() {
        this.isComposeOpen = true;
        this.render();
    }

    closeComposeForm() {
        this.isComposeOpen = false;
        this.render();
    }

    handleSearch(event) {
        if (this.searchTimeout) {
            clearTimeout(this.searchTimeout);
        }
        this.searchQuery = event.target.value;
        this.searchTimeout = setTimeout(() => {
            this.currentPage = 1;
            this.render();
        }, 1000);
    }

    handleFilter(event) {
        this.currentFilter = event.target.dataset.filter;
        this.currentPage = 1;
        this.render();
    }

    prevPage() {
        if (this.currentPage > 1) {
            this.currentPage--;
            this.render();
        }
    }

    nextPage() {
        const totalPages = Math.ceil(this.getFilteredMails().length / this.itemsPerPage);
        if (this.currentPage < totalPages) {
            this.currentPage++;
            this.render();
        }
    }

    deleteMail(event) {
        const id = event.target.dataset.id;
        const index = this.mails.findIndex(m => m.id === id);
        if (index !== -1) {
            const confirmed = window.confirm("Are you sure you want to delete this email?");
            if (confirmed) {
                this.mails.splice(index, 1);
                this.render();
            }
        }
        event.stopPropagation();
    }

    viewMail(event) {
        const id = event.target.closest('.mail-row').dataset.id;
        this.viewingMail = this.mails.find(m => m.id === id);

        if (!this.viewingMail.seen) {
            this.viewingMail.seen = true;
        }
        if(!this.viewingMail.fullBody) {
            this.content.innerHTML = `<div class="loading-spinner">Loading...</div>`;
            this.widget.callService('getFullMailBody', {id: this.viewingMail.id}).subscribe(data => {
                this.viewingMail.html = data.body;
                this.render();
            });
        } else {
            this.render();
        }
    }

    formatDate(date) {
        const now = new Date();
        const inputDate = new Date(date);

        // Check if the date is today
        if (now.toDateString() === inputDate.toDateString()) {
            // Format as hours:minutes (24-hour format)
            const hours = inputDate.getHours().toString().padStart(2, '0');
            const minutes = inputDate.getMinutes().toString().padStart(2, '0');
            return `${hours}:${minutes}`;
        } else {
            if (now.getFullYear() === inputDate.getFullYear()) {
                // Format as "day month"
                const options = { day: 'numeric', month: 'short' };
                return inputDate.toLocaleDateString('en-GB', options);
            } else {
                // Format as "dd.mm.yyyy"
                const day = inputDate.getDate().toString().padStart(2, '0');
                const month = (inputDate.getMonth() + 1).toString().padStart(2, '0');
                const year = inputDate.getFullYear();
                return `${day}.${month}.${year}`;
            }
        }
    }

    getFilteredMails() {
        let filtered = this.mails.filter(mail => {
            if (this.searchQuery) {
                const query = this.searchQuery.toLowerCase();
                if(!(mail.sender.toLowerCase().includes(query) ||
                    mail.subject.toLowerCase().includes(query) ||
                    mail.preview?.toLowerCase().includes(query))) {
                    return false
                }
            }
            if (this.currentFilter === 'unread') return !mail.seen;
            if (this.currentFilter === 'attachments') return mail.attachments?.length > 0;
            return true;
        });

        filtered.sort((a, b) => {
            let valA, valB;
            switch (this.sortBy) {
                case 'sender':
                    valA = a.sender.toLowerCase();
                    valB = b.sender.toLowerCase();
                    break;
                case 'date':
                    valA = a.receivedDate || 0;
                    valB = b.receivedDate || 0;
                    break;
                case 'subject':
                    valA = a.subject.toLowerCase();
                    valB = b.subject.toLowerCase();
                    break;
                default:
                    return 0;
            }

            if (valA < valB) return this.sortDir === 'asc' ? -1 : 1;
            if (valA > valB) return this.sortDir === 'asc' ? 1 : -1;
            return 0;
        });

        return filtered;
    }
}

customElements.define("mail-widget", MailWidget);